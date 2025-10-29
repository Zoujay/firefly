package firefly.service.messagecenter;


import firefly.bean.dto.JobBuildDto;
import firefly.bean.dto.JobConfigDto;
import firefly.bean.dto.StageBuildDto;
import firefly.bean.dto.StageConfigDto;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.KafkaConfiguration;
import firefly.constant.PluginType;
import firefly.service.jobbuild.IJobBuildService;
import firefly.service.jobconfig.IJobConfigService;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.pluginparser.PluginServiceParser;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.impl.StageConfigServiceImpl;
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
    private IStageBuildService stageBuildService;

    @Autowired
    private IJobBuildService jobBuildService;

    @Autowired
    private IJobConfigService jobConfigService;

    @Autowired
    private KafkaTemplate<String,Object> kafkaTemplate;
    @Autowired
    private StageConfigServiceImpl stageConfigService;


    public Boolean onPipelineMessage(TriggerPipelineMessage pipelineMessage) {
        // step 1. modify pipeline status
        Long pipelineBuildID = pipelineMessage.getPipelineBuildID();
        BuildStatus buildStatus = pipelineMessage.getBuildStatus();
        Boolean result = pipelineBuildService.updatePipelineBuildStatus(pipelineBuildID, buildStatus);
        // generate stage message
        TriggerStageMessage triggerStageMessage = this.assembleTriggerStageMessage(pipelineBuildID, buildStatus);
        kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
        return true;
    }

    public Boolean onStageMessage(TriggerStageMessage stageMessage) {
        // step 1. modify stage status
        Long stageBuildID = stageMessage.getStageBuildID();
        BuildStatus buildStatus = stageMessage.getBuildStatus();
        Boolean result = stageBuildService.updateStageBuildStatusByID(buildStatus, stageBuildID);
        // assemble job message
        List<TriggerJobMessage> jobMessages = this.assembleTriggerJobMessages(stageBuildID);
        kafkaTemplate.send(KafkaConfiguration.JOB_TOPIC, jobMessages);
        return true;
    }


    public Boolean onJobMessages(List<TriggerJobMessage> jobMessages) {
        // step 1. modify job status
        for (TriggerJobMessage jobMessage : jobMessages) {
            Long jobBuildID = jobMessage.getJobBuildID();
            BuildStatus buildStatus = jobMessage.getBuildStatus();
            JobBuildDto jobBuildDto = jobBuildService.getJobBuildByID(jobBuildID);
            Long stageBuildID = jobBuildDto.getStageBuildID();
            TriggerStageMessage triggerStageMessage = this.assembleTriggerStageMessage(stageBuildID, BuildStatus.RUNNING);
            if (buildStatus.equals(BuildStatus.SUCCESS)) {
                // check stage status
                StageBuildDto stageBuildDto = stageBuildService.getStageBuildByID(stageBuildID);
                Long stageConfigID = stageBuildDto.getStageConfigID();
                StageConfigDto stageConfigDto = stageConfigService.getStageConfigByID(stageConfigID);
                Boolean isJobParallel = stageConfigDto.getIsJobParallel();
                List<JobBuildDto> jobBuildDtos = jobBuildService.getJobBuildsByStageBuildStage(stageBuildID);
                if(isJobParallel) {
                    BuildStatus status = jobBuildService.checkParallelStageStatus(jobBuildDtos);
                    triggerStageMessage.setBuildStatus(status);
                }else {
                    JobBuildDto lastOne = jobBuildDtos.get(jobBuildDtos.size() - 1);
                    if(lastOne.getJobBuildID().equals(jobBuildDto.getJobBuildID())) {
                        triggerStageMessage.setBuildStatus(BuildStatus.SUCCESS);
                    }else {
                        triggerStageMessage.setBuildStatus(BuildStatus.RUNNING);
                    }
                }
                kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
                return true;
            }
            if(buildStatus.equals(BuildStatus.FAILURE)) {
                // check stage status
                triggerStageMessage.setBuildStatus(BuildStatus.FAILURE);
                kafkaTemplate.send(KafkaConfiguration.STAGE_TOPIC, triggerStageMessage);
                return true;
            }
            Boolean result = jobBuildService.updateJobBuildStatus(jobBuildID, buildStatus);
            Long jobConfigID = jobBuildDto.getJobConfigID();
            JobConfigDto jobConfigDto = jobConfigService.getJobConfigByID(jobConfigID);
            Long pluginID= jobConfigDto.getPluginID();
            PluginType pluginType = jobConfigDto.getPluginType();
            Boolean pluginResult = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).executePluginBuild(pluginID, BuildStatus.RUNNING);
        }
        return true;
    }

    public Boolean onPluginMessage(TriggerPluginMessage pluginMessage) {
        PluginType pluginType = pluginMessage.getPluginType();
        Long pluginBuildID = pluginMessage.getPluginBuildID();
        BuildStatus status = pluginMessage.getStatus();
        Boolean pluginResult = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).updatePluginBuild(pluginBuildID, status);
        System.out.println("update plugin build status");
        Long jobBuildID = PluginServiceParser.PLUGIN_BUILD_MAP.get(pluginType).getJobBuild(pluginBuildID);
        TriggerJobMessage triggerJobMessage = new TriggerJobMessage();
        triggerJobMessage.setJobBuildID(jobBuildID)
                .setBuildStatus(BuildStatus.SUCCESS)
                .setMessageUUID(UUID.randomUUID().toString());
        if(pluginResult) {
            if (Objects.requireNonNull(status) == BuildStatus.SUCCESS) {
                triggerJobMessage.setBuildStatus(BuildStatus.SUCCESS);
            } else {
                triggerJobMessage.setBuildStatus(BuildStatus.FAILURE);
            }
        }
        kafkaTemplate.send(KafkaConfiguration.JOB_TOPIC, triggerJobMessage);
        return true;
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


    private List<TriggerJobMessage> assembleTriggerJobMessages(Long stageBuildID) {
        List<TriggerJobMessage> triggerJobMessageList = new ArrayList<>();
        List<JobBuildDto> jobBuildDtos = jobBuildService.getJobBuildsByStageBuildStage(stageBuildID);
        for(JobBuildDto jobBuildDto : jobBuildDtos) {
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
