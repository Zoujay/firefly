package firefly.service.messagecenter;


import firefly.bean.dto.*;
import firefly.bean.dto.message.KafkaBusinessMessage;
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
import firefly.service.pluginbuild.IPluginBuild;
import firefly.service.pluginparser.PluginServiceParser;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
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
        Boolean updated = pipelineBuildService.updatePipelineBuildStatus(pipelineBuildID, buildStatus);
        if (!Boolean.TRUE.equals(updated)) {
            throw new IllegalStateException(
                    "Failed to update pipeline build: pipelineBuildID="
                            + pipelineBuildID + ", status=" + buildStatus);
        }
        if (buildStatus.equals(BuildStatus.SUCCESS)) {
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            return true;
        }
        // generate stage message
        TriggerStageMessage triggerStageMessage = this.assembleTriggerStageMessage(pipelineBuildID, buildStatus);
        send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
        return true;
    }


    public Boolean onStageMessage(TriggerStageMessage stageMessage) {
        // step 1. modify stage status
        Long stageBuildID = stageMessage.getStageBuildID();
        StageBuildDto stageBuildDto = stageBuildService.getStageBuildByID(stageBuildID);
        Long stageConfigID = stageBuildDto.getStageConfigID();
        Long pipelineBuildID = stageBuildDto.getPipelineBuildID();
        BuildStatus buildStatus = stageMessage.getBuildStatus();
        Boolean updated = stageBuildService.updateStageBuildStatusByID(buildStatus, stageBuildID);
        if (!Boolean.TRUE.equals(updated)) {
            throw new IllegalStateException(
                    "Failed to update stage build: stageBuildID="
                            + stageBuildID + ", status=" + buildStatus);
        }
        StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
        Long pipelineID = stageConfigDto.getPipelineID();
        if (buildStatus.equals(BuildStatus.SUCCESS)) {

            List<StageConfigDto> stageConfigDtos = stageConfigService.getStageConfigsByPipelineID(pipelineID);
            int currentStageIndex = -1;
            for (int i = 0; i < stageConfigDtos.size(); i++) {
                if (stageConfigID.equals(stageConfigDtos.get(i).getId())) {
                    currentStageIndex = i;
                    break;
                }
            }
            if (currentStageIndex < 0) {
                throw new IllegalStateException(
                        "Stage config " + stageConfigID + " does not belong to pipeline " + pipelineID);
            }
            if (currentStageIndex == stageConfigDtos.size() - 1) {
                TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
                triggerPipelineMessage.setPipelineBuildID(pipelineBuildID)
                        .setMessageUUID(BusinessMessageUUID.pipeline(pipelineBuildID, buildStatus))
                        .setBuildStatus(buildStatus)
                        .setPipelineID(pipelineID);
                send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);
            } else {
                // trigger next stage
                Long nextStageConfigID = stageConfigDtos.get(currentStageIndex + 1).getId();
                StageBuildDto next = stageBuildService.getStageBuildByStageConfigIDAndPipelineBuildID(
                        nextStageConfigID, pipelineBuildID);
                if (next != null) {
                    TriggerStageMessage triggerStageMessage = this.assembleTriggerStageByJobMessage(
                            next.getStageBuildID(), BuildStatus.RUNNING);
                    send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
                }
            }
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
            triggerPipelineMessage.setPipelineBuildID(pipelineBuildID)
                    .setMessageUUID(BusinessMessageUUID.pipeline(pipelineBuildID, BuildStatus.FAILURE))
                    .setBuildStatus(BuildStatus.FAILURE)
                    .setPipelineID(pipelineID);
            send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);
            return true;
        }
        // assemble job message
        List<TriggerJobMessage> jobMessages = this.assembleTriggerHeadJobMessages(stageConfigID, stageBuildID);
        for (TriggerJobMessage jobMessage : jobMessages) {
            send(KafkaConfiguration.JOB_TOPIC, jobMessage);
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
        if (!Boolean.TRUE.equals(result)) {
            throw new IllegalStateException(
                    "Failed to update job build: jobBuildID="
                            + jobBuildID + ", status=" + buildStatus);
        }
        TriggerStageMessage triggerStageMessage = this.assembleTriggerStageByJobMessage(stageBuildID, BuildStatus.RUNNING);
        StageBuildDto stageBuildDto = stageBuildService.getStageBuildByID(stageBuildID);
        if (buildStatus == BuildStatus.SUCCESS) {
            // todo check stage status
            if (stageBuildDto.getStatus() == BuildStatus.FAILURE) {
                return false;
            }
            Long stageConfigID = stageBuildDto.getStageConfigID();
            StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
            JobRelationDto jobRelationDto = jobRelationService.getNextJobRelation(
                    stageConfigDto.getId(), jobBuildDto.getJobConfigID());
            Long triggerNextJobID = jobRelationDto.getNextJobID();
            JobBuildDto nextJobBuildDto = jobBuildService.getJobBuildByJobConfigIDAndStageBuildID(triggerNextJobID, stageBuildID);
            if (nextJobBuildDto != null) {
                TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
                Long nextJobBuildID = nextJobBuildDto.getJobBuildID();
                triggerJobMessage.setMessageUUID(BusinessMessageUUID.job(nextJobBuildID, BuildStatus.RUNNING));
                triggerJobMessage.setJobBuildID(nextJobBuildID);
                triggerJobMessage.setBuildStatus(BuildStatus.RUNNING);
                send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
            } else {
                List<JobBuildDto> tailJobs = jobBuildService.getTailJobBuildsByStageBuildID(stageConfigID, stageBuildID);
                BuildStatus status = jobBuildService.calculateStageStatus(tailJobs);
                if (!isTerminalStatus(status)) {
                    return true;
                }
                Boolean transitioned = stageBuildService.transitionStageBuildStatus(
                        stageBuildID,
                        BuildStatus.RUNNING,
                        status);
                if (!Boolean.TRUE.equals(transitioned)) {
                    log.debug(
                            "Stage {} terminal transition has already been handled, target={}",
                            stageBuildID,
                            status);
                    return true;
                }
                triggerStageMessage.setBuildStatus(status);
                triggerStageMessage.setMessageUUID(BusinessMessageUUID.stage(stageBuildID, status));
                send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            }
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            Boolean transitioned = stageBuildService.transitionStageBuildStatus(
                    stageBuildID,
                    BuildStatus.RUNNING,
                    BuildStatus.FAILURE);
            if (!Boolean.TRUE.equals(transitioned)) {
                log.debug(
                        "Stage {} failure transition has already been handled",
                        stageBuildID);
                return true;
            }
            triggerStageMessage.setBuildStatus(BuildStatus.FAILURE);
            triggerStageMessage.setMessageUUID(BusinessMessageUUID.stage(stageBuildID, BuildStatus.FAILURE));
            send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            return true;
        }

        Long jobConfigID = jobBuildDto.getJobConfigID();
        JobConfigDto jobConfigDto = jobConfigService.getJobConfigByID(jobConfigID);
        PluginType pluginType = jobConfigDto.getPluginType();
        Long pluginBuildID = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).getPluginBuildIDByJobBuildID(jobBuildID);
        return PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).executePluginBuild(pluginBuildID, BuildStatus.RUNNING);
    }

    private boolean isTerminalStatus(BuildStatus status) {
        return status == BuildStatus.SUCCESS || status == BuildStatus.FAILURE;
    }

    public Boolean onPluginMessage(TriggerPluginMessage pluginMessage) {
        PluginType pluginType = pluginMessage.getPluginType();
        Long pluginBuildID = pluginMessage.getPluginBuildID();
        BuildStatus status = pluginMessage.getStatus();

        IPluginBuild pluginBuildService = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType);
        if (pluginBuildService == null) {
            throw new IllegalStateException("Unsupported plugin type: " + pluginType);
        }
        if (pluginBuildID == null || pluginBuildID <= 0L) {
            throw new IllegalStateException("Invalid plugin build ID: " + pluginBuildID);
        }
        if (!isTerminalStatus(status)) {
            throw new IllegalStateException(
                    "Plugin message is not terminal: pluginBuildID="
                            + pluginBuildID + ", status=" + status);
        }

        Long jobBuildID = pluginBuildService.getJobBuildID(pluginBuildID);
        if (jobBuildID == null || jobBuildID <= 0L) {
            throw new IllegalStateException(
                    "Job build not found for pluginBuildID=" + pluginBuildID);
        }

        Boolean pluginResult = pluginBuildService.updatePluginBuild(pluginBuildID, status);
        if (!Boolean.TRUE.equals(pluginResult)) {
            throw new IllegalStateException(
                    "Failed to update plugin build: pluginBuildID="
                            + pluginBuildID + ", status=" + status);
        }

        TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
        triggerJobMessage.setJobBuildID(jobBuildID)
                .setBuildStatus(status)
                .setMessageUUID(BusinessMessageUUID.job(jobBuildID, status));
        send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
        return true;
    }


    private TriggerStageMessage assembleTriggerStageByJobMessage(Long stageBuildID, BuildStatus status) {
        TriggerStageMessage stageMessage = new TriggerStageMessage();
        stageMessage.setMessageUUID(BusinessMessageUUID.stage(stageBuildID, status))
                .setStageBuildID(stageBuildID)
                .setBuildStatus(status);
        return stageMessage;
    }


    private TriggerStageMessage assembleTriggerStageMessage(Long pipelineBuildID, BuildStatus status) {
        TriggerStageMessage stageMessage = new TriggerStageMessage();

        StageBuildDto stageBuildDto = stageBuildService.getFirstStageToRun(pipelineBuildID);
        Long stageBuildID = stageBuildDto.getStageBuildID();
        stageMessage.setMessageUUID(BusinessMessageUUID.stage(stageBuildID, status))
                .setStageBuildID(stageBuildID)
                .setBuildStatus(status);
        return stageMessage;

    }


    private List<TriggerJobMessage> assembleTriggerHeadJobMessages(Long stageConfigID, Long stageBuildID) {
        List<TriggerJobMessage> triggerJobMessageList = new ArrayList<>();
        List<JobBuildDto> jobBuildDtos = jobBuildService.getHeadJobBuildsByStageBuildID(stageConfigID, stageBuildID);
        for (JobBuildDto jobBuildDto : jobBuildDtos) {
            TriggerJobMessage jobMessage = new TriggerJobMessage();
            Long jobBuildID = jobBuildDto.getJobBuildID();
            jobMessage.setMessageUUID(BusinessMessageUUID.job(jobBuildID, BuildStatus.RUNNING))
                    .setJobBuildID(jobBuildID)
                    .setBuildStatus(BuildStatus.RUNNING);
            triggerJobMessageList.add(jobMessage);
        }
        return triggerJobMessageList;
    }

    private void send(String topic, KafkaBusinessMessage message) {
        kafkaTemplate.send(topic, message.getMessageUUID(), message);
    }

}
