package firefly.service.messagecenter;


import firefly.bean.dto.*;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.KafkaConfiguration;
import firefly.constant.PluginType;
import firefly.service.jobbuild.IJobBuildService;
import firefly.service.jobconfig.IJobConfigService;
import firefly.service.jobconfig.IJobRelationService;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.pluginparser.PluginServiceParser;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MessageCenter {

    @Autowired
    private IPipelineBuildService pipelineBuildService;

    @Autowired
    private IPipelineConfigService pipelineConfigService;

    @Autowired
    private IStageConfigService stageConfigService;

    @Autowired
    private IStageBuildService stageBuildService;

    @Autowired
    private IJobBuildService jobBuildService;

    @Autowired
    private IJobConfigService jobConfigService;

    @Autowired
    private IJobRelationService jobRelationService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;


    public Boolean onPipelineMessage(TriggerPipelineMessage pipelineMessage) {
        // step 1. modify pipeline status
        Long pipelineBuildID = pipelineMessage.getPipelineBuildID();
        BuildStatus buildStatus = pipelineMessage.getBuildStatus();
        pipelineBuildService.updatePipelineBuildStatus(pipelineBuildID, buildStatus);
        if (buildStatus.equals(BuildStatus.SUCCESS)) {
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            return true;
        }
        // generate stage message
        TriggerStageMessage triggerStageMessage = this.assembleTriggerStageMessage(pipelineBuildID, buildStatus);
        kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
        return true;
    }


    public Boolean onStageMessage(TriggerStageMessage stageMessage) {
        // step 1. modify stage status
        Long stageBuildID = stageMessage.getStageBuildID();
        StageBuildDto stageBuildDto = stageBuildService.getStageBuildByID(stageBuildID);
        Long stageConfigID = stageBuildDto.getStageConfigID();
        BuildStatus buildStatus = stageMessage.getBuildStatus();
        stageBuildService.updateStageBuildStatusByID(buildStatus, stageBuildID);
        StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
        Long pipelineID = stageConfigDto.getPipelineID();
        if (buildStatus.equals(BuildStatus.SUCCESS)) {

            List<StageConfigDto> stageConfigDtos = stageConfigService.getStageConfigsByPipelineID(pipelineID);
            StageConfigDto lastStage = stageConfigDtos.getLast();
            if (lastStage.getId().equals(stageConfigID)) {
                TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
                Long pipelineBuildID = stageBuildDto.getPipelineBuildID();
                triggerPipelineMessage.setPipelineBuildID(pipelineBuildID)
                        .setMessageUUID(UUID.randomUUID().toString())
                        .setBuildStatus(buildStatus)
                        .setPipelineID(pipelineID);
                kafkaTemplate.send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);
            }else {
                // trigger next stage
                Long nextStageConfigID = 0L;
                for(int i = 0;i < stageConfigDtos.size();i++) {
                    StageConfigDto dto = stageConfigDtos.get(i);
                    if(stageConfigID.equals(dto.getId())) {
                        nextStageConfigID = stageConfigDtos.get(i+1).getId();
                        break;
                    }
                }
                StageBuildDto next = stageBuildService.getStageBuildByStageConfigID(nextStageConfigID);
                if(next != null) {
                    TriggerStageMessage triggerStageMessage = this.assembleTriggerStageByJobMessage(next.getStageBuildID(), buildStatus);
                    kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
                }
            }
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            Long pipelineBuildID = stageBuildDto.getPipelineBuildID();
            TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
            triggerPipelineMessage.setPipelineBuildID(pipelineBuildID)
                    .setMessageUUID(UUID.randomUUID().toString())
                    .setBuildStatus(BuildStatus.SUCCESS)
                    .setPipelineID(pipelineID);
            kafkaTemplate.send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);
            return true;
        }
        // assemble job message
        List<TriggerJobMessage> jobMessages = this.assembleTriggerHeadJobMessages(stageConfigID, stageBuildID);
        for (TriggerJobMessage jobMessage : jobMessages) {
            kafkaTemplate.send(KafkaConfiguration.JOB_TOPIC, jobMessage);
        }
        return true;
    }


    public Boolean onJobMessage(TriggerJobMessage jobMessage) {
        // step 1. modify job status
        Long jobBuildID = jobMessage.getJobBuildID();
        BuildStatus buildStatus = jobMessage.getBuildStatus();
        JobBuildDto jobBuildDto = jobBuildService.getJobBuildByID(jobBuildID);
        Long stageBuildID = jobBuildDto.getStageBuildID();
        Boolean result = jobBuildService.updateJobBuildStatus(jobBuildID, buildStatus);
        TriggerStageMessage triggerStageMessage = this.assembleTriggerStageByJobMessage(stageBuildID, BuildStatus.RUNNING);
        if (buildStatus == BuildStatus.SUCCESS) {
            // todo check stage status
            StageBuildDto stageBuildDto = stageBuildService.getStageBuildByID(stageBuildID);
            if(stageBuildDto.getStatus() == BuildStatus.FAILURE) {
                return false;
            }
            Long stageConfigID = stageBuildDto.getStageConfigID();
            StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
            JobRelationDto jobRelationDto = jobRelationService.getNextJobRelation(
                    stageConfigDto.getId(), jobBuildDto.getJobConfigID());
            Long triggerNextJobID = jobRelationDto.getNextJobID();
            JobBuildDto nextJobBuildDto = jobBuildService.getJobBuildByJobConfigIDAndStageBuildID(triggerNextJobID, stageBuildID);
            if(nextJobBuildDto != null) {
                TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
                triggerJobMessage.setMessageUUID(UUID.randomUUID().toString());
                triggerJobMessage.setJobBuildID(nextJobBuildDto.getJobBuildID());
                triggerJobMessage.setBuildStatus(BuildStatus.RUNNING);
                kafkaTemplate.send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
            }else {
                List<JobBuildDto> tailJobs= jobBuildService.getTailJobBuildsByStageBuildID(stageConfigID, stageBuildID);
                BuildStatus status = jobBuildService.calculateStageStatus(tailJobs);
                triggerStageMessage.setBuildStatus(status);
                kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            }
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            // check stage status
            triggerStageMessage.setBuildStatus(BuildStatus.FAILURE);
            kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            return true;
        }

        Long jobConfigID = jobBuildDto.getJobConfigID();
        JobConfigDto jobConfigDto = jobConfigService.getJobConfigByID(jobConfigID);
        PluginType pluginType = jobConfigDto.getPluginType();
        Long pluginBuildID = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).getPluginBuildIDByJobBuildID(jobBuildID);
        return PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).executePluginBuild(pluginBuildID, BuildStatus.RUNNING);
    }

    public Boolean onPluginMessage(TriggerPluginMessage pluginMessage) {
        PluginType pluginType = pluginMessage.getPluginType();
        Long pluginBuildID = pluginMessage.getPluginBuildID();
        BuildStatus status = pluginMessage.getStatus();
        Boolean pluginResult = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).updatePluginBuild(pluginBuildID, status);
        System.out.println("update plugin build status");
        Long jobBuildID = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).getJobBuildID(pluginBuildID);
        TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
        triggerJobMessage.setJobBuildID(jobBuildID)
                .setBuildStatus(BuildStatus.SUCCESS)
                .setMessageUUID(UUID.randomUUID().toString());
        if (pluginResult) {
            if (Objects.requireNonNull(status) == BuildStatus.SUCCESS) {
                triggerJobMessage.setBuildStatus(BuildStatus.SUCCESS);
            } else {
                triggerJobMessage.setBuildStatus(BuildStatus.FAILURE);
            }
        }
        kafkaTemplate.send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
        return true;
    }


    private TriggerStageMessage assembleTriggerStageByJobMessage(Long stageBuildID, BuildStatus status) {
        TriggerStageMessage stageMessage = new TriggerStageMessage();
        UUID uuid = UUID.randomUUID();
        stageMessage.setMessageUUID(uuid.toString())
                .setStageBuildID(stageBuildID)
                .setBuildStatus(status);
        return stageMessage;
    }


    private TriggerStageMessage assembleTriggerStageMessage(Long pipelineBuildID, BuildStatus status) {
        TriggerStageMessage stageMessage = new TriggerStageMessage();
        UUID uuid = UUID.randomUUID();

        StageBuildDto stageBuildDto = stageBuildService.getFirstStageToRun(pipelineBuildID);
        stageMessage.setMessageUUID(uuid.toString())
                .setStageBuildID(stageBuildDto.getStageBuildID())
                .setBuildStatus(status);
        return stageMessage;

    }


    private List<TriggerJobMessage> assembleTriggerHeadJobMessages(Long stageConfigID, Long stageBuildID) {
        List<TriggerJobMessage> triggerJobMessageList = new ArrayList<>();
        List<JobBuildDto> jobBuildDtos = jobBuildService.getHeadJobBuildsByStageBuildID(stageConfigID, stageBuildID);
        for (JobBuildDto jobBuildDto : jobBuildDtos) {
            TriggerJobMessage jobMessage = new TriggerJobMessage();
            UUID uuid = UUID.randomUUID();
            jobMessage.setMessageUUID(uuid.toString())
                    .setJobBuildID(jobBuildDto.getJobBuildID())
                    .setBuildStatus(BuildStatus.RUNNING);
            triggerJobMessageList.add(jobMessage);
        }
        return triggerJobMessageList;
    }

}
