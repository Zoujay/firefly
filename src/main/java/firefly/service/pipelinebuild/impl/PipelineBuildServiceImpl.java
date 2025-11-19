package firefly.service.pipelinebuild.impl;


import firefly.bean.dto.*;
import firefly.bean.dto.message.BaseMessage;
import firefly.bean.vo.request.PipelineBuildRequest;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import firefly.constant.TriggerOrigin;
import firefly.dao.pipelinebuild.IPipelineBuildDao;
import firefly.model.pipeline.PipelineBuild;
import firefly.service.jobbuild.impl.JobBuildServiceImpl;
import firefly.service.jobconfig.IJobConfigService;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.pluginbuild.IPluginBuild;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.impl.StageConfigServiceServiceImpl;
import firefly.service.trigger.TriggerCenter;
import firefly.service.triggerorigin.OriginCenter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static firefly.service.pluginparser.PluginServiceParser.PLUGIN_BUILD_MAP;

@Slf4j
@Service
@Transactional
public class PipelineBuildServiceImpl implements IPipelineBuildService {

    @Autowired
    private IPipelineBuildDao pipelineBuildDao;

    @Autowired
    private IPipelineConfigService pipelineConfig;

    @Autowired
    private IStageBuildService stageBuildService;
    @Autowired
    private IJobConfigService jobConfig;
    @Autowired
    private StageConfigServiceServiceImpl stageConfigService;

    @Autowired
    private JobBuildServiceImpl jobBuildService;

    @Autowired
    private TriggerCenter triggerCenter;

    @Override
    public Boolean updatePipelineBuildStatus(Long pipelineBuildID, BuildStatus status) {
        int result = pipelineBuildDao.updatePipelineBuildStatus(pipelineBuildID, status);
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
        PipelineConfigDto pipelineConfigDto = pipelineConfig.getPipelineConfigDtoByID(pipelineBuild.getPipelineID());
        PipelineBuildDto pipelineBuildDto = new  PipelineBuildDto();
        pipelineBuildDto.setBuildStatus(pipelineBuild.getPipelineStatus())
                .setPipelineID(pipelineBuild.getPipelineID())
                .setTriggerOrigin(TriggerOrigin.valueOf(pipelineConfigDto.getTriggerOrigin()));
        return pipelineBuildDto;
    }

    @Override
    public PipelineBuildDto parsePipelineBuildRequest(PipelineBuildRequest pipelineBuildRequest) {
        PipelineBuildDto pipelineBuildDto = new PipelineBuildDto();
        pipelineBuildDto.setPipelineID(pipelineBuildRequest.getPipelineId())
                .setPipelineUUID(pipelineBuildRequest.getUuid())
                .setTriggerOrigin(pipelineBuildRequest.getTriggerOrigin())
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
    public Long buildPipeline(PipelineBuildDto pipelineBuildDto) {
        Long pipelineBuildId = this.savePipelineBuild(pipelineBuildDto);
        Long pipelineID = pipelineBuildDto.getPipelineID();
        if(pipelineBuildId == null || pipelineBuildId <= 0L) {
            System.out.println("trigger pipeline failed");
            return -1L;
        }
        List<StageConfigDto> stages = stageConfigService.getStageConfigsByPipelineID(pipelineID);
        for (StageConfigDto stageConfig : stages) {
            StageBuildDto stageBuildDto = this.assembleStageBuildDto(
                    stageConfig.getId(), pipelineBuildId, BuildStatus.PENDING);
            Long stageBuildID = stageBuildService.saveStageBuild(stageBuildDto);
            System.out.println("stage build id: " + stageBuildID);
            List<JobConfigDto> jobConfigs = jobConfig.getJobConfigsByStageID(stageConfig.getId());
            for (JobConfigDto jobConfig : jobConfigs) {
                Long jobConfigID = jobConfig.getId();
                Long pluginID = jobConfig.getPluginID();
                PluginType pluginType = jobConfig.getPluginType();
                JobBuildDto jobBuildDto = this.assembleJobBuildDto(jobConfigID, stageBuildID, BuildStatus.PENDING);
                Long jobBuildID = jobBuildService.saveJobBuild(jobBuildDto);
                IPluginBuild pluginBuildService = PLUGIN_BUILD_MAP.get(pluginType);
                JobBuildContext jobBuildContext = new JobBuildContext();
                jobBuildContext.setJobBuildID(jobBuildID)
                        .setJobConfigID(jobConfigID).setPluginType(pluginType)
                        .setPluginID(pluginID).setStatus(BuildStatus.PENDING);
                pluginBuildService.savePluginBuild(jobBuildContext);

            }
        }
        return pipelineBuildId;
    }

    @Override
    public BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID) {
        return OriginCenter.TriggerOriginMap.get(pipelineBuildDto.getTriggerOrigin()).buildMessage(pipelineBuildDto, pipelineBuildID);
    }

    private PipelineBuild assemblePipelineBuild(PipelineBuildDto pipelineBuildDto) {
        PipelineBuild pipelineBuild = new PipelineBuild();
        pipelineBuild.setPipelineStatus(pipelineBuildDto.getBuildStatus())
                .setPipelineID(pipelineBuildDto.getPipelineID());

        return pipelineBuild;
    }

    private StageBuildDto assembleStageBuildDto(Long stageID, Long pipelineBuildId, BuildStatus status) {
        StageBuildDto stageBuildDto = new StageBuildDto();
        stageBuildDto.setStageConfigID(stageID)
                .setPipelineBuildID(pipelineBuildId)
                .setStatus(status);
        return stageBuildDto;
    }

    private JobBuildDto assembleJobBuildDto(Long jobID, Long stageBuildID, BuildStatus status) {
        JobBuildDto jobBuildDto = new JobBuildDto();
        jobBuildDto.setJobConfigID(jobID)
                .setStageBuildID(stageBuildID)
                .setStatus(status);
        return jobBuildDto;
    }


    private boolean checkCanJobRunning(Boolean isJobParallel, int jobIndex){
        if(isJobParallel) {
            return true;
        }
        return jobIndex == 0;
    }


}
