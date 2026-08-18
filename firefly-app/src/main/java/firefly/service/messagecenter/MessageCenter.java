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
import firefly.service.outbox.OutboxService;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.pluginbuild.IPluginBuild;
import firefly.service.pluginparser.PluginServiceParser;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Transactional
public class MessageCenter {

    @Autowired private IPipelineBuildService pipelineBuildService;

    @Autowired private IPipelineConfigService pipelineConfigService;

    @Autowired private IStageConfigService stageConfigService;

    @Autowired private IStageBuildService stageBuildService;

    @Autowired private IJobBuildService jobBuildService;

    @Autowired private IJobConfigService jobConfigService;

    @Autowired private IJobRelationService jobRelationService;

    @Autowired private OutboxService outboxService;

    public Boolean onPipelineMessage(TriggerPipelineMessage pipelineMessage) {
        // step 1. modify pipeline status
        Long pipelineBuildID = pipelineMessage.getPipelineBuildID();
        BuildStatus buildStatus = pipelineMessage.getBuildStatus();
        Integer executionAttempt = pipelineMessage.getExecutionAttempt();
        Boolean updated =
                pipelineBuildService.updatePipelineBuildStatus(
                        pipelineBuildID, buildStatus, executionAttempt);
        if (!Boolean.TRUE.equals(updated)) {
            throw new IllegalStateException(
                    "Failed to update pipeline build: pipelineBuildID="
                            + pipelineBuildID
                            + ", status="
                            + buildStatus);
        }
        if (buildStatus.equals(BuildStatus.SUCCESS)) {
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            return true;
        }
        // generate stage message
        TriggerStageMessage triggerStageMessage =
                this.assembleTriggerStageMessage(pipelineBuildID, buildStatus);
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
        Integer executionAttempt = stageMessage.getExecutionAttempt();
        Boolean updated =
                stageBuildService.updateStageBuildStatusByID(
                        buildStatus, stageBuildID, executionAttempt);
        if (!Boolean.TRUE.equals(updated)) {
            throw new IllegalStateException(
                    "Failed to update stage build: stageBuildID="
                            + stageBuildID
                            + ", status="
                            + buildStatus);
        }
        StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
        Long pipelineID = stageConfigDto.getPipelineID();
        if (buildStatus.equals(BuildStatus.SUCCESS)) {

            List<StageConfigDto> stageConfigDtos =
                    stageConfigService.getStageConfigsByPipelineID(pipelineID);
            int currentStageIndex = -1;
            for (int i = 0; i < stageConfigDtos.size(); i++) {
                if (stageConfigID.equals(stageConfigDtos.get(i).getId())) {
                    currentStageIndex = i;
                    break;
                }
            }
            if (currentStageIndex < 0) {
                throw new IllegalStateException(
                        "Stage config "
                                + stageConfigID
                                + " does not belong to pipeline "
                                + pipelineID);
            }
            StageBuildDto nextStageBuild = null;
            for (int i = currentStageIndex + 1; i < stageConfigDtos.size(); i++) {
                Long nextStageConfigID = stageConfigDtos.get(i).getId();
                StageBuildDto candidate =
                        stageBuildService.getStageBuildByStageConfigIDAndPipelineBuildID(
                                nextStageConfigID, pipelineBuildID);
                if (candidate == null) {
                    throw new IllegalStateException(
                            "Stage build not found: pipelineBuildID="
                                    + pipelineBuildID
                                    + ", stageConfigID="
                                    + nextStageConfigID);
                }
                if (candidate.getStatus() != BuildStatus.SUCCESS) {
                    nextStageBuild = candidate;
                    break;
                }
            }
            if (nextStageBuild == null) {
                TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
                triggerPipelineMessage
                        .setPipelineBuildID(pipelineBuildID)
                        .setMessageUUID(
                                BusinessMessageUUID.pipeline(
                                        pipelineBuildID, executionAttempt, buildStatus))
                        .setBuildStatus(buildStatus)
                        .setExecutionAttempt(executionAttempt)
                        .setPipelineID(pipelineID);
                send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);
            } else {
                TriggerStageMessage triggerStageMessage =
                        this.assembleTriggerStageByJobMessage(
                                nextStageBuild.getStageBuildID(),
                                BuildStatus.RUNNING,
                                nextStageBuild.getExecutionAttempt());
                send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            }
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
            triggerPipelineMessage
                    .setPipelineBuildID(pipelineBuildID)
                    .setMessageUUID(
                            BusinessMessageUUID.pipeline(
                                    pipelineBuildID, executionAttempt, BuildStatus.FAILURE))
                    .setBuildStatus(BuildStatus.FAILURE)
                    .setExecutionAttempt(executionAttempt)
                    .setPipelineID(pipelineID);
            send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);
            return true;
        }
        // assemble job message
        List<TriggerJobMessage> jobMessages =
                this.assembleTriggerRunnableJobMessages(stageConfigID, stageBuildID);
        for (TriggerJobMessage jobMessage : jobMessages) {
            send(KafkaConfiguration.JOB_TOPIC, jobMessage);
        }
        if (jobMessages.isEmpty()) {
            completeStageIfReady(stageConfigID, stageBuildID, executionAttempt);
        }
        return true;
    }

    public Boolean onJobMessage(TriggerJobMessage jobMessage) {
        // step 1. modify job status
        Long jobBuildID = jobMessage.getJobBuildID();
        BuildStatus buildStatus = jobMessage.getBuildStatus();
        Integer executionAttempt = jobMessage.getExecutionAttempt();
        JobBuildDto jobBuildDto = jobBuildService.getJobBuildByID(jobBuildID);
        Long stageBuildID = jobBuildDto.getStageBuildID();
        StageBuildDto stageBuildDto = null;
        if (isTerminalStatus(buildStatus)) {
            // All terminal Job results for the same Stage must update and
            // re-evaluate completion one at a time. The lock is held by the
            // surrounding message transaction until its Outbox write commits.
            stageBuildDto = stageBuildService.lockStageBuild(stageBuildID, executionAttempt);
        }
        Boolean result =
                jobBuildService.updateJobBuildStatus(jobBuildID, buildStatus, executionAttempt);
        if (!Boolean.TRUE.equals(result)) {
            throw new IllegalStateException(
                    "Failed to update job build: jobBuildID="
                            + jobBuildID
                            + ", status="
                            + buildStatus);
        }
        TriggerStageMessage triggerStageMessage =
                this.assembleTriggerStageByJobMessage(
                        stageBuildID, BuildStatus.RUNNING, executionAttempt);
        if (buildStatus == BuildStatus.SUCCESS) {
            // todo check stage status
            if (stageBuildDto.getStatus() == BuildStatus.FAILURE) {
                return false;
            }
            Long stageConfigID = stageBuildDto.getStageConfigID();
            StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
            JobBuildDto nextJobBuildDto =
                    findNextRunnableJob(
                            stageConfigDto.getId(), stageBuildID, jobBuildDto.getJobConfigID());
            if (nextJobBuildDto != null) {
                TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
                Long nextJobBuildID = nextJobBuildDto.getJobBuildID();
                triggerJobMessage.setMessageUUID(
                        BusinessMessageUUID.job(
                                nextJobBuildID,
                                nextJobBuildDto.getExecutionAttempt(),
                                BuildStatus.RUNNING));
                triggerJobMessage.setJobBuildID(nextJobBuildID);
                triggerJobMessage.setExecutionAttempt(nextJobBuildDto.getExecutionAttempt());
                triggerJobMessage.setBuildStatus(BuildStatus.RUNNING);
                send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
            } else {
                List<JobBuildDto> tailJobs =
                        jobBuildService.getTailJobBuildsForUpdate(stageConfigID, stageBuildID);
                BuildStatus status = jobBuildService.calculateStageStatus(tailJobs);
                if (!isTerminalStatus(status)) {
                    return true;
                }
                Boolean transitioned =
                        stageBuildService.transitionStageBuildStatus(
                                stageBuildID, BuildStatus.RUNNING, status, executionAttempt);
                if (!Boolean.TRUE.equals(transitioned)) {
                    log.debug(
                            "Stage {} terminal transition has already been handled, target={}",
                            stageBuildID,
                            status);
                    return true;
                }
                triggerStageMessage.setBuildStatus(status);
                triggerStageMessage.setMessageUUID(
                        BusinessMessageUUID.stage(stageBuildID, executionAttempt, status));
                send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            }
            return true;
        }
        if (buildStatus.equals(BuildStatus.FAILURE)) {
            Boolean transitioned =
                    stageBuildService.transitionStageBuildStatus(
                            stageBuildID,
                            BuildStatus.RUNNING,
                            BuildStatus.FAILURE,
                            executionAttempt);
            if (!Boolean.TRUE.equals(transitioned)) {
                log.debug("Stage {} failure transition has already been handled", stageBuildID);
                return true;
            }
            triggerStageMessage.setBuildStatus(BuildStatus.FAILURE);
            triggerStageMessage.setMessageUUID(
                    BusinessMessageUUID.stage(stageBuildID, executionAttempt, BuildStatus.FAILURE));
            send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
            return true;
        }

        Long jobConfigID = jobBuildDto.getJobConfigID();
        JobConfigDto jobConfigDto = jobConfigService.getJobConfigByID(jobConfigID);
        PluginType pluginType = jobConfigDto.getPluginType();
        Long pluginBuildID =
                PluginServiceParser.PLUGIN_BUILD_MAP
                        .get(pluginType)
                        .getPluginBuildIDByJobBuildID(jobBuildID);
        return PluginServiceParser.PLUGIN_BUILD_MAP
                .get(pluginType)
                .executePluginBuild(pluginBuildID, BuildStatus.RUNNING, executionAttempt);
    }

    private boolean isTerminalStatus(BuildStatus status) {
        return status == BuildStatus.SUCCESS || status == BuildStatus.FAILURE;
    }

    public Boolean onPluginMessage(TriggerPluginMessage pluginMessage) {
        PluginType pluginType = pluginMessage.getPluginType();
        Long pluginBuildID = pluginMessage.getPluginBuildID();
        BuildStatus status = pluginMessage.getStatus();
        Integer executionAttempt = pluginMessage.getExecutionAttempt();

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
                            + pluginBuildID
                            + ", status="
                            + status);
        }

        Long jobBuildID = pluginBuildService.getJobBuildID(pluginBuildID);
        if (jobBuildID == null || jobBuildID <= 0L) {
            throw new IllegalStateException(
                    "Job build not found for pluginBuildID=" + pluginBuildID);
        }

        Boolean pluginResult =
                pluginBuildService.updatePluginBuild(pluginBuildID, status, executionAttempt);
        if (!Boolean.TRUE.equals(pluginResult)) {
            throw new IllegalStateException(
                    "Failed to update plugin build: pluginBuildID="
                            + pluginBuildID
                            + ", status="
                            + status);
        }

        TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
        triggerJobMessage
                .setJobBuildID(jobBuildID)
                .setBuildStatus(status)
                .setExecutionAttempt(executionAttempt)
                .setMessageUUID(BusinessMessageUUID.job(jobBuildID, executionAttempt, status));
        send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
        return true;
    }

    private TriggerStageMessage assembleTriggerStageByJobMessage(
            Long stageBuildID, BuildStatus status, Integer executionAttempt) {
        TriggerStageMessage stageMessage = new TriggerStageMessage();
        stageMessage
                .setMessageUUID(BusinessMessageUUID.stage(stageBuildID, executionAttempt, status))
                .setStageBuildID(stageBuildID)
                .setExecutionAttempt(executionAttempt)
                .setBuildStatus(status);
        return stageMessage;
    }

    private TriggerStageMessage assembleTriggerStageMessage(
            Long pipelineBuildID, BuildStatus status) {
        TriggerStageMessage stageMessage = new TriggerStageMessage();

        StageBuildDto stageBuildDto = stageBuildService.getFirstStageToRun(pipelineBuildID);
        Long stageBuildID = stageBuildDto.getStageBuildID();
        Integer executionAttempt = stageBuildDto.getExecutionAttempt();
        stageMessage
                .setMessageUUID(BusinessMessageUUID.stage(stageBuildID, executionAttempt, status))
                .setStageBuildID(stageBuildID)
                .setExecutionAttempt(executionAttempt)
                .setBuildStatus(status);
        return stageMessage;
    }

    private List<TriggerJobMessage> assembleTriggerRunnableJobMessages(
            Long stageConfigID, Long stageBuildID) {
        List<TriggerJobMessage> triggerJobMessageList = new ArrayList<>();
        List<JobBuildDto> jobBuildDtos =
                jobBuildService.getRunnableJobBuildsByStageBuildID(stageConfigID, stageBuildID);
        for (JobBuildDto jobBuildDto : jobBuildDtos) {
            TriggerJobMessage jobMessage = new TriggerJobMessage();
            Long jobBuildID = jobBuildDto.getJobBuildID();
            Integer executionAttempt = jobBuildDto.getExecutionAttempt();
            jobMessage
                    .setMessageUUID(
                            BusinessMessageUUID.job(
                                    jobBuildID, executionAttempt, BuildStatus.RUNNING))
                    .setJobBuildID(jobBuildID)
                    .setExecutionAttempt(executionAttempt)
                    .setBuildStatus(BuildStatus.RUNNING);
            triggerJobMessageList.add(jobMessage);
        }
        return triggerJobMessageList;
    }

    private JobBuildDto findNextRunnableJob(
            Long stageConfigID, Long stageBuildID, Long currentJobConfigID) {
        JobRelationDto relation =
                jobRelationService.getNextJobRelation(stageConfigID, currentJobConfigID);
        while (relation != null
                && relation.getNextJobID() != null
                && relation.getNextJobID() > 0L) {
            Long nextJobConfigID = relation.getNextJobID();
            JobBuildDto candidate =
                    jobBuildService.getJobBuildByJobConfigIDAndStageBuildID(
                            nextJobConfigID, stageBuildID);
            if (candidate == null) {
                return null;
            }
            if (candidate.getStatus() != BuildStatus.SUCCESS) {
                return candidate;
            }
            relation = jobRelationService.getNextJobRelation(stageConfigID, nextJobConfigID);
        }
        return null;
    }

    private void completeStageIfReady(
            Long stageConfigID, Long stageBuildID, Integer executionAttempt) {
        List<JobBuildDto> tailJobs =
                jobBuildService.getTailJobBuildsByStageBuildID(stageConfigID, stageBuildID);
        BuildStatus status = jobBuildService.calculateStageStatus(tailJobs);
        if (status != BuildStatus.SUCCESS) {
            return;
        }
        Boolean transitioned =
                stageBuildService.transitionStageBuildStatus(
                        stageBuildID, BuildStatus.RUNNING, BuildStatus.SUCCESS, executionAttempt);
        if (!Boolean.TRUE.equals(transitioned)) {
            return;
        }
        TriggerStageMessage stageMessage =
                assembleTriggerStageByJobMessage(
                        stageBuildID, BuildStatus.SUCCESS, executionAttempt);
        send(KafkaConfiguration.STAGE_TOPIC, stageMessage);
    }

    private void send(String topic, KafkaBusinessMessage message) {
        /*
         * Do not send Kafka inside the business transaction. enqueue() uses
         * Propagation.MANDATORY, so the build-state change and Outbox row are
         * atomic in MySQL; the actual Kafka send begins only after commit.
         */
        outboxService.enqueue(topic, message);
    }
}
