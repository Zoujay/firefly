package firefly.service.pipelineconfig.impl;

import firefly.bean.dto.JobConfigDto;
import firefly.bean.dto.JobRelationDto;
import firefly.bean.dto.PipelineConfigDto;
import firefly.bean.dto.StageConfigDto;
import firefly.bean.vo.request.JobConfigRequest;
import firefly.bean.vo.request.PipelineConfigRequest;
import firefly.bean.vo.request.StageConfigRequest;
import firefly.bean.vo.response.JobConfigResponse;
import firefly.bean.vo.response.PipelineConfigResponse;
import firefly.bean.vo.response.StageConfigResponse;
import firefly.constant.PluginType;
import firefly.dao.jobconfig.IJobConfigDao;
import firefly.dao.pipelineconfig.IPipelineConfigDao;
import firefly.model.job.JobModel;
import firefly.model.pipeline.PipelineModel;
import firefly.service.jobconfig.IJobRelationService;
import firefly.service.jobconfig.impl.JobConfigServiceServiceImpl;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.pluginconfig.IPluginConfig;
import firefly.service.pluginparser.PluginServiceParser;
import firefly.service.stageconfig.impl.StageConfigServiceServiceImpl;
import firefly.service.triggerorigin.OriginCenter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PipelineConfigServiceImpl implements IPipelineConfigService {

    @Autowired
    private IPipelineConfigDao pipelineConfigDao;

    @Autowired
    private IJobConfigDao jobConfigDao;

    @Autowired
    private StageConfigServiceServiceImpl stageConfigServiceImpl;

    @Autowired
    private JobConfigServiceServiceImpl jobConfigService;

    @Autowired
    private IJobRelationService jobRelationService;

    @Override
    @Transactional
    public String createPipeline(PipelineConfigRequest pipelineConfigRequest) {
        PipelineModel pipelineModel = this.assemblePipelineModel(pipelineConfigRequest, -1L);
        pipelineConfigDao.save(pipelineModel);
        Long pipelineId = pipelineModel.getId();

        // save origin info
        Long originID = OriginCenter.TriggerOriginMap
                .get(pipelineModel.getTriggerOrigin())
                .saveTriggerOrigin(pipelineConfigRequest.getOriginInfo(), pipelineId);
        pipelineModel.setOriginID(originID);
        pipelineConfigDao.save(pipelineModel);
        for (StageConfigRequest stageConfigRequest : pipelineConfigRequest.getStageConfigs()) {
            StageConfigDto stageConfigDto = stageConfigServiceImpl.createStage(stageConfigRequest, pipelineId);
            List<List<JobConfigRequest>> jobs = stageConfigRequest.getJobConfigs();
            List<JobModel> jobModels = new ArrayList<>();
            for (List<JobConfigRequest> jobList : jobs) {
                for (JobConfigRequest job : jobList) {
                    JobModel jobModel = jobConfigService.assembleJobModel(job, stageConfigDto.getId(), 0L);
                    jobConfigDao.save(jobModel);
                    PluginType type = job.getPluginType();
                    IPluginConfig pluginConfigService = PluginServiceParser.PLUGIN_MAP.get(type);
                    Long pluginID = pluginConfigService.savePlugin(job.getPluginRaw(), jobModel.getId());
                    jobModel.setPluginID(pluginID);
                    jobConfigDao.save(jobModel);
                    jobModels.add(jobModel);
                }
                for (int i = 0; i < jobModels.size(); i++) {
                    JobModel jobModel = jobModels.get(i);
                    Long nextJobID = 0L;
                    Long preJobID = 0L;
                    Boolean isHead = false;
                    if (i < jobModels.size() - 1) {
                        nextJobID = jobModels.get(i + 1).getId();
                    }
                    if (i > 0) {
                        preJobID = jobModels.get(i - 1).getId();
                    }
                    if (i == 0) {
                        isHead = true;
                    }
                    JobRelationDto jobRelationDto = assembleJobRelationDto(
                            jobModel.getId(), pipelineId, stageConfigDto.getId(), nextJobID, preJobID, isHead
                    );
                    jobRelationService.saveJobRelation(jobRelationDto);
                }
            }
        }
        return pipelineConfigRequest.getUuid();
    }

    @Override
    public PipelineConfigResponse getPipelineConfigByUUID(String pipelineUUID) {
        PipelineModel pipelineModel = pipelineConfigDao.getPipelineConfigByPipelineUUID(pipelineUUID);
        if (pipelineModel == null) {
            return null;
        }
        PipelineConfigDto pipelineConfigDto = this.assemblePipelineConfigDto(pipelineModel);
        Long pipelineID = pipelineModel.getId();
        List<StageConfigDto> stageConfigDtos = stageConfigServiceImpl.getStageConfigsByPipelineID(pipelineID);
        List<StageConfigResponse> stageConfigResponses = new ArrayList<>();
        for (StageConfigDto stageDto : stageConfigDtos) {
            List<JobConfigDto> jobConfigDtos = jobConfigService.getJobConfigsByStageID(stageDto.getId());
            List<JobConfigResponse> jobConfigResponses = new ArrayList<>();
            for (JobConfigDto jobConfigDto : jobConfigDtos) {
                JobConfigResponse jobConfigResponse = jobConfigService.assembleJobConfigResponse(jobConfigDto);
                jobConfigResponses.add(jobConfigResponse);
            }
            StageConfigResponse stageConfigResponse = stageConfigServiceImpl.assembleConfigResponse(stageDto, jobConfigResponses);
            stageConfigResponses.add(stageConfigResponse);
        }
        return this.assemblePipelineConfigResponse(pipelineConfigDto, stageConfigResponses);
    }

    @Override
    public PipelineConfigDto getPipelineConfigDtoByID(Long pipelineID) {
        Optional<PipelineModel> pipelineModel = pipelineConfigDao.findById(pipelineID);
        return pipelineModel.map(this::assemblePipelineConfigDto).orElse(null);
    }

    @Override
    public PipelineConfigDto assemblePipelineConfigDto(PipelineModel pipelineModel) {
        PipelineConfigDto pipelineConfigDto = new PipelineConfigDto();
        pipelineConfigDto.setUuid(pipelineModel.getPipelineUUID());
        pipelineConfigDto.setId(pipelineModel.getId());
        pipelineConfigDto.setTriggerMode(pipelineModel.getTriggerMode().name());
        pipelineConfigDto.setTriggerMatch(pipelineModel.getTriggerMatch().name());
        pipelineConfigDto.setName(pipelineModel.getPipelineName());
        pipelineConfigDto.setName(pipelineModel.getTriggerOrigin().name());
        return pipelineConfigDto;
    }

    @Override
    public PipelineConfigResponse assemblePipelineConfigResponse(PipelineConfigDto pipelineConfigDto, List<StageConfigResponse> stageConfigResponses) {
        PipelineConfigResponse pipelineConfigResponse = new PipelineConfigResponse();
        pipelineConfigResponse.setId(pipelineConfigDto.getId());
        pipelineConfigResponse.setUuid(pipelineConfigDto.getUuid());
        pipelineConfigResponse.setName(pipelineConfigDto.getName());
        pipelineConfigResponse.setTriggerMode(pipelineConfigDto.getTriggerMode());
        pipelineConfigResponse.setTriggerMatch(pipelineConfigDto.getTriggerMatch());
        pipelineConfigResponse.setStageConfigs(stageConfigResponses);
        pipelineConfigResponse.setTriggerOrigin(pipelineConfigDto.getTriggerOrigin());

        return pipelineConfigResponse;
    }

    @Override
    public PipelineModel assemblePipelineModel(PipelineConfigRequest request, Long originID) {
        PipelineModel pipelineModel = new PipelineModel();
        pipelineModel.setPipelineUUID(request.getUuid());
        pipelineModel.setPipelineName(request.getName());
        pipelineModel.setTriggerMode(request.getTriggerModel());
        pipelineModel.setTriggerMatch(request.getTriggerMatch());
        pipelineModel.setTriggerOrigin(request.getTriggerOrigin());
        pipelineModel.setOriginID(originID);
        return pipelineModel;
    }

    private JobRelationDto assembleJobRelationDto(
            Long jobID,
            Long pipelineID,
            Long stageID,
            Long nextJobID,
            Long previousJobID,
            Boolean isHeadJob
    ) {
        JobRelationDto jobRelationDto = new JobRelationDto();
        jobRelationDto.setJobID(jobID)
                .setPipelineID(pipelineID)
                .setStageID(stageID)
                .setNextJobID(nextJobID)
                .setPreviousJobID(previousJobID)
                .setIsHeadJob(isHeadJob);
        return jobRelationDto;
    }

}
