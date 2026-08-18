package firefly.service.pipelinebuild.impl;

import static firefly.service.pluginparser.PluginServiceParser.PLUGIN_BUILD_MAP;

import firefly.bean.dto.*;
import firefly.bean.dto.message.BaseMessage;
import firefly.bean.vo.request.PipelineBuildRequest;
import firefly.bean.vo.response.PipelineRetryResponse;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import firefly.constant.TriggerOrigin;
import firefly.dao.jobbuild.IJobBuildDao;
import firefly.dao.pipelinebuild.IPipelineBuildDao;
import firefly.dao.pluginbuild.ITextPluginBuildDao;
import firefly.dao.stagebuild.IStageBuildDao;
import firefly.model.job.JobBuild;
import firefly.model.pipeline.PipelineBuild;
import firefly.model.plugin.TextPluginBuild;
import firefly.model.stage.StageBuild;
import firefly.service.jobbuild.impl.JobBuildServiceImpl;
import firefly.service.jobconfig.IJobConfigService;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.pipelinebuild.PipelineRetryNotAllowedException;
import firefly.service.pipelinebuild.PipelineRetryPreparedEvent;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.pluginbuild.IPluginBuild;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.impl.StageConfigServiceServiceImpl;
import firefly.service.trigger.TriggerCenter;
import firefly.service.triggerorigin.OriginCenter;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
public class PipelineBuildServiceImpl implements IPipelineBuildService {

  @Autowired private IPipelineBuildDao pipelineBuildDao;

  @Autowired private IStageBuildDao stageBuildDao;

  @Autowired private IJobBuildDao jobBuildDao;

  @Autowired private ITextPluginBuildDao textPluginBuildDao;

  @Autowired private ApplicationEventPublisher applicationEventPublisher;

  @Autowired private IPipelineConfigService pipelineConfig;

  @Autowired private IStageBuildService stageBuildService;
  @Autowired private IJobConfigService jobConfig;
  @Autowired private StageConfigServiceServiceImpl stageConfigService;

  @Autowired private JobBuildServiceImpl jobBuildService;

  @Autowired private TriggerCenter triggerCenter;

  @Override
  public Boolean updatePipelineBuildStatus(
      Long pipelineBuildID, BuildStatus status, Integer executionAttempt) {
    int result =
        pipelineBuildDao.updatePipelineBuildStatus(pipelineBuildID, status, executionAttempt);
    return result == 1;
  }

  @Override
  public Long savePipelineBuild(PipelineBuildDto pipelineBuildDto) {
    PipelineBuild pipelineBuild = this.assemblePipelineBuild(pipelineBuildDto);
    pipelineBuildDao.save(pipelineBuild);
    return pipelineBuild.getId();
  }

  @Override
  public PipelineBuildDto getPipelineBuild(Long pipelineBuildID) {
    Optional<PipelineBuild> entity = pipelineBuildDao.findById(pipelineBuildID);
    if (entity.isEmpty()) {
      return null;
    }
    PipelineBuild pipelineBuild = entity.get();
    PipelineConfigDto pipelineConfigDto =
        pipelineConfig.getPipelineConfigDtoByID(pipelineBuild.getPipelineID());
    PipelineBuildDto pipelineBuildDto = new PipelineBuildDto();
    pipelineBuildDto
        .setBuildStatus(pipelineBuild.getPipelineStatus())
        .setPipelineID(pipelineBuild.getPipelineID())
        .setExecutionAttempt(pipelineBuild.getExecutionAttempt())
        .setTriggerOrigin(TriggerOrigin.valueOf(pipelineConfigDto.getTriggerOrigin()));
    return pipelineBuildDto;
  }

  @Override
  public PipelineBuildDto parsePipelineBuildRequest(PipelineBuildRequest pipelineBuildRequest) {
    PipelineBuildDto pipelineBuildDto = new PipelineBuildDto();
    pipelineBuildDto
        .setPipelineID(pipelineBuildRequest.getPipelineId())
        .setPipelineUUID(pipelineBuildRequest.getUuid())
        .setTriggerOrigin(pipelineBuildRequest.getTriggerOrigin())
        .setExecutionAttempt(0)
        .setBuildStatus(BuildStatus.PENDING);
    return pipelineBuildDto;
  }

  @Override
  public Long triggerPipeline(PipelineBuildRequest pipelineBuildRequest) {
    PipelineBuildDto pipelineBuildDto = this.parsePipelineBuildRequest(pipelineBuildRequest);
    Long pipelineBuildID = this.buildPipeline(pipelineBuildDto);
    BaseMessage message = this.buildMessage(pipelineBuildDto, pipelineBuildID);
    triggerCenter.dispatch(message);
    return pipelineBuildID;
  }

  @Override
  public PipelineRetryResponse retryPipeline(Long pipelineBuildID) {
    int claimed =
        pipelineBuildDao.claimRetry(pipelineBuildID, BuildStatus.FAILURE, BuildStatus.RUNNING);
    if (claimed != 1) {
      throw new PipelineRetryNotAllowedException(
          "Pipeline build does not exist or is not in FAILURE: " + pipelineBuildID);
    }

    PipelineBuild pipelineBuild =
        pipelineBuildDao
            .findById(pipelineBuildID)
            .orElseThrow(
                () ->
                    new PipelineRetryNotAllowedException(
                        "Pipeline build does not exist: " + pipelineBuildID));
    Integer executionAttempt = pipelineBuild.getExecutionAttempt();
    List<StageBuild> stageBuilds = stageBuildDao.getStageBuildByPipelineBuildID(pipelineBuildID);
    StageBuild firstStageToRetry = null;

    for (StageBuild stageBuild : stageBuilds) {
      if (stageBuild.getStageStatus() == BuildStatus.SUCCESS) {
        continue;
      }
      if (firstStageToRetry == null) {
        firstStageToRetry = stageBuild;
      }
      stageBuild.setStageStatus(BuildStatus.PENDING).setExecutionAttempt(executionAttempt);

      List<JobBuild> jobBuilds = jobBuildDao.getJobBuildsByStageBuildID(stageBuild.getId());
      for (JobBuild jobBuild : jobBuilds) {
        if (jobBuild.getJobStatus() == BuildStatus.SUCCESS) {
          continue;
        }
        jobBuild.setJobStatus(BuildStatus.PENDING).setExecutionAttempt(executionAttempt);
        textPluginBuildDao
            .findByJobBuildID(jobBuild.getId())
            .ifPresent(pluginBuild -> resetPluginBuild(pluginBuild, executionAttempt));
      }
      jobBuildDao.saveAll(jobBuilds);
    }

    if (firstStageToRetry == null) {
      throw new PipelineRetryNotAllowedException(
          "Pipeline build has no failed or unfinished stage: " + pipelineBuildID);
    }

    stageBuildDao.saveAll(stageBuilds);
    applicationEventPublisher.publishEvent(
        new PipelineRetryPreparedEvent(firstStageToRetry.getId(), executionAttempt));
    return new PipelineRetryResponse(pipelineBuildID, executionAttempt);
  }

  @Override
  public Long buildPipeline(PipelineBuildDto pipelineBuildDto) {
    Long pipelineBuildId = this.savePipelineBuild(pipelineBuildDto);
    Long pipelineID = pipelineBuildDto.getPipelineID();
    if (pipelineBuildId == null || pipelineBuildId <= 0L) {
      log.error("Failed to create pipeline build: pipelineID={}", pipelineID);
      return -1L;
    }
    List<StageConfigDto> stages = stageConfigService.getStageConfigsByPipelineID(pipelineID);
    for (StageConfigDto stageConfig : stages) {
      StageBuildDto stageBuildDto =
          this.assembleStageBuildDto(
              stageConfig.getId(),
              pipelineBuildId,
              BuildStatus.PENDING,
              pipelineBuildDto.getExecutionAttempt());
      Long stageBuildID = stageBuildService.saveStageBuild(stageBuildDto);
      log.debug(
          "Created stage build: stageBuildID={}, pipelineBuildID={}",
          stageBuildID,
          pipelineBuildId);
      List<JobConfigDto> jobConfigs = jobConfig.getJobConfigsByStageID(stageConfig.getId());
      for (JobConfigDto jobConfig : jobConfigs) {
        Long jobConfigID = jobConfig.getId();
        Long pluginID = jobConfig.getPluginID();
        PluginType pluginType = jobConfig.getPluginType();
        JobBuildDto jobBuildDto =
            this.assembleJobBuildDto(
                jobConfigID,
                stageBuildID,
                BuildStatus.PENDING,
                pipelineBuildDto.getExecutionAttempt());
        Long jobBuildID = jobBuildService.saveJobBuild(jobBuildDto);
        IPluginBuild pluginBuildService = PLUGIN_BUILD_MAP.get(pluginType);
        JobBuildContext jobBuildContext = new JobBuildContext();
        jobBuildContext
            .setJobBuildID(jobBuildID)
            .setJobConfigID(jobConfigID)
            .setPluginType(pluginType)
            .setPluginID(pluginID)
            .setStatus(BuildStatus.PENDING)
            .setExecutionAttempt(pipelineBuildDto.getExecutionAttempt());
        pluginBuildService.savePluginBuild(jobBuildContext);
      }
    }
    return pipelineBuildId;
  }

  @Override
  public BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID) {
    return OriginCenter.TriggerOriginMap.get(pipelineBuildDto.getTriggerOrigin())
        .buildMessage(pipelineBuildDto, pipelineBuildID);
  }

  private PipelineBuild assemblePipelineBuild(PipelineBuildDto pipelineBuildDto) {
    PipelineBuild pipelineBuild = new PipelineBuild();
    pipelineBuild
        .setPipelineStatus(pipelineBuildDto.getBuildStatus())
        .setPipelineID(pipelineBuildDto.getPipelineID())
        .setExecutionAttempt(pipelineBuildDto.getExecutionAttempt());

    return pipelineBuild;
  }

  private StageBuildDto assembleStageBuildDto(
      Long stageID, Long pipelineBuildId, BuildStatus status, Integer executionAttempt) {
    StageBuildDto stageBuildDto = new StageBuildDto();
    stageBuildDto
        .setStageConfigID(stageID)
        .setPipelineBuildID(pipelineBuildId)
        .setExecutionAttempt(executionAttempt)
        .setStatus(status);
    return stageBuildDto;
  }

  private JobBuildDto assembleJobBuildDto(
      Long jobID, Long stageBuildID, BuildStatus status, Integer executionAttempt) {
    JobBuildDto jobBuildDto = new JobBuildDto();
    jobBuildDto
        .setJobConfigID(jobID)
        .setStageBuildID(stageBuildID)
        .setExecutionAttempt(executionAttempt)
        .setStatus(status);
    return jobBuildDto;
  }

  private void resetPluginBuild(TextPluginBuild pluginBuild, Integer executionAttempt) {
    pluginBuild.setTextPluginStatus(BuildStatus.PENDING).setExecutionAttempt(executionAttempt);
    textPluginBuildDao.save(pluginBuild);
  }
}
