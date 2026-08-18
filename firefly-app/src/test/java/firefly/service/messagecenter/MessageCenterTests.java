package firefly.service.messagecenter;

import static firefly.constant.KafkaConfiguration.JOB_TOPIC;
import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import firefly.bean.dto.JobBuildDto;
import firefly.bean.dto.JobRelationDto;
import firefly.bean.dto.StageBuildDto;
import firefly.bean.dto.StageConfigDto;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class MessageCenterTests {

    @Mock
    private IPipelineBuildService pipelineBuildService;

    @Mock
    private IPipelineConfigService pipelineConfigService;

    @Mock
    private IStageConfigService stageConfigService;

    @Mock
    private IStageBuildService stageBuildService;

    @Mock
    private IJobBuildService jobBuildService;

    @Mock
    private IJobConfigService jobConfigService;

    @Mock
    private IJobRelationService jobRelationService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private IPluginBuild pluginBuildService;

    @InjectMocks
    private MessageCenter messageCenter;

    private IPluginBuild previousTextPluginBuildService;

    @BeforeEach
    void registerPluginBuildService() {
        previousTextPluginBuildService =
            PluginServiceParser.PLUGIN_BUILD_MAP.put(PluginType.TEXT, pluginBuildService);
    }

    @AfterEach
    void restorePluginBuildService() {
        if (previousTextPluginBuildService == null) {
            PluginServiceParser.PLUGIN_BUILD_MAP.remove(PluginType.TEXT);
        } else {
            PluginServiceParser.PLUGIN_BUILD_MAP.put(
                PluginType.TEXT, previousTextPluginBuildService);
        }
    }

    @Test
    void propagatesStageFailureToPipeline() {
        StageBuildDto stageBuild =
            new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        StageConfigDto stageConfig = new StageConfigDto().setId(20L).setPipelineID(40L);
        TriggerStageMessage message =
            new TriggerStageMessage()
                .setMessageUUID("stage-failure")
                .setStageBuildID(10L)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.FAILURE);

        when(stageBuildService.getStageBuildByID(10L)).thenReturn(stageBuild);
        when(stageBuildService.updateStageBuildStatusByID(BuildStatus.FAILURE, 10L, 0))
            .thenReturn(true);
        when(stageConfigService.getStageConfigByID(20L)).thenReturn(stageConfig);

        messageCenter.onStageMessage(message);

        ArgumentCaptor<TriggerPipelineMessage> captor =
            ArgumentCaptor.forClass(TriggerPipelineMessage.class);
        verify(outboxService).enqueue(eq(PIPELINE_TOPIC), captor.capture());
        assertEquals(BuildStatus.FAILURE, captor.getValue().getBuildStatus());
        assertEquals(30L, captor.getValue().getPipelineBuildID());
        assertEquals(
            BusinessMessageUUID.pipeline(30L, 0, BuildStatus.FAILURE),
            captor.getValue().getMessageUUID());
    }

    @Test
    void triggersNextOrderedStageInTheSamePipelineBuild() {
        StageBuildDto currentStageBuild =
            new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        StageBuildDto nextStageBuild =
            new StageBuildDto()
                .setStageBuildID(11L)
                .setStageConfigID(21L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.PENDING);
        StageConfigDto currentStage =
            new StageConfigDto().setId(20L).setPipelineID(40L).setStageOrder(0);
        StageConfigDto nextStage =
            new StageConfigDto().setId(21L).setPipelineID(40L).setStageOrder(1);
        TriggerStageMessage message =
            new TriggerStageMessage()
                .setMessageUUID("stage-success")
                .setStageBuildID(10L)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.SUCCESS);

        when(stageBuildService.getStageBuildByID(10L)).thenReturn(currentStageBuild);
        when(stageBuildService.updateStageBuildStatusByID(BuildStatus.SUCCESS, 10L, 0))
            .thenReturn(true);
        when(stageConfigService.getStageConfigByID(20L)).thenReturn(currentStage);
        when(stageConfigService.getStageConfigsByPipelineID(40L))
            .thenReturn(List.of(currentStage, nextStage));
        when(stageBuildService.getStageBuildByStageConfigIDAndPipelineBuildID(21L, 30L))
            .thenReturn(nextStageBuild);

        messageCenter.onStageMessage(message);

        ArgumentCaptor<TriggerStageMessage> captor =
            ArgumentCaptor.forClass(TriggerStageMessage.class);
        verify(outboxService).enqueue(eq(STAGE_TOPIC), captor.capture());
        assertEquals(11L, captor.getValue().getStageBuildID());
        assertEquals(BuildStatus.RUNNING, captor.getValue().getBuildStatus());
        assertEquals(
            BusinessMessageUUID.stage(11L, 0, BuildStatus.RUNNING),
            captor.getValue().getMessageUUID());
    }

    @Test
    void publishesStageSuccessOnlyWhenAtomicTransitionWins() {
        TriggerJobMessage message = prepareTailJobSuccess();
        when(stageBuildService.transitionStageBuildStatus(
            10L, BuildStatus.RUNNING, BuildStatus.SUCCESS, 0))
            .thenReturn(true);

        messageCenter.onJobMessage(message);

        var terminalOrder = inOrder(stageBuildService, jobBuildService);
        terminalOrder.verify(stageBuildService).lockStageBuild(10L, 0);
        terminalOrder.verify(jobBuildService).updateJobBuildStatus(50L, BuildStatus.SUCCESS, 0);

        ArgumentCaptor<TriggerStageMessage> captor =
            ArgumentCaptor.forClass(TriggerStageMessage.class);
        verify(outboxService).enqueue(eq(STAGE_TOPIC), captor.capture());
        assertEquals(10L, captor.getValue().getStageBuildID());
        assertEquals(BuildStatus.SUCCESS, captor.getValue().getBuildStatus());
        assertEquals(
            BusinessMessageUUID.stage(10L, 0, BuildStatus.SUCCESS),
            captor.getValue().getMessageUUID());
    }

    @Test
    void doesNotPublishStageSuccessWhenAnotherThreadWonAtomicTransition() {
        TriggerJobMessage message = prepareTailJobSuccess();
        when(stageBuildService.transitionStageBuildStatus(
            10L, BuildStatus.RUNNING, BuildStatus.SUCCESS, 0))
            .thenReturn(false);

        messageCenter.onJobMessage(message);

        verify(outboxService, never())
            .enqueue(
                eq(STAGE_TOPIC),
                org.mockito.ArgumentMatchers.any(TriggerStageMessage.class));
    }

    @Test
    void propagatesPluginSuccessToJob() {
        assertPluginTerminalStatusPropagates(BuildStatus.SUCCESS);
    }

    @Test
    void propagatesPluginFailureToJob() {
        assertPluginTerminalStatusPropagates(BuildStatus.FAILURE);
    }

    @Test
    void doesNotPublishJobMessageWhenPluginUpdateFails() {
        TriggerPluginMessage message = pluginMessage(BuildStatus.FAILURE);
        when(pluginBuildService.getJobBuildID(70L)).thenReturn(50L);
        when(pluginBuildService.updatePluginBuild(70L, BuildStatus.FAILURE, 0)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> messageCenter.onPluginMessage(message));

        verify(outboxService, never()).enqueue(eq(JOB_TOPIC), any());
    }

    @Test
    void doesNotUpdatePluginOrPublishJobWhenJobMappingIsMissing() {
        TriggerPluginMessage message = pluginMessage(BuildStatus.FAILURE);
        when(pluginBuildService.getJobBuildID(70L)).thenReturn(-1L);

        assertThrows(IllegalStateException.class, () -> messageCenter.onPluginMessage(message));

        verify(pluginBuildService, never())
            .updatePluginBuild(eq(70L), eq(BuildStatus.FAILURE), eq(0));
        verify(outboxService, never()).enqueue(eq(JOB_TOPIC), any());
    }

    @Test
    void rejectsNonTerminalPluginMessageWithoutPublishingJob() {
        TriggerPluginMessage message = pluginMessage(BuildStatus.RUNNING);

        assertThrows(IllegalStateException.class, () -> messageCenter.onPluginMessage(message));

        verify(pluginBuildService, never()).getJobBuildID(70L);
        verify(pluginBuildService, never())
            .updatePluginBuild(eq(70L), eq(BuildStatus.RUNNING), eq(0));
        verify(outboxService, never()).enqueue(eq(JOB_TOPIC), any());
    }

    @Test
    void propagatesJobFailureToStageWhenJobUpdateSucceeds() {
        JobBuildDto jobBuild =
            new JobBuildDto()
                .setJobBuildID(50L)
                .setJobConfigID(60L)
                .setStageBuildID(10L)
                .setStatus(BuildStatus.RUNNING);
        StageBuildDto stageBuild =
            new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        TriggerJobMessage message =
            new TriggerJobMessage()
                .setMessageUUID("job-failure")
                .setJobBuildID(50L)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.FAILURE);
        when(jobBuildService.getJobBuildByID(50L)).thenReturn(jobBuild);
        when(jobBuildService.updateJobBuildStatus(50L, BuildStatus.FAILURE, 0)).thenReturn(true);
        when(stageBuildService.lockStageBuild(10L, 0)).thenReturn(stageBuild);
        when(stageBuildService.transitionStageBuildStatus(
            10L, BuildStatus.RUNNING, BuildStatus.FAILURE, 0))
            .thenReturn(true);

        messageCenter.onJobMessage(message);

        ArgumentCaptor<TriggerStageMessage> captor =
            ArgumentCaptor.forClass(TriggerStageMessage.class);
        verify(outboxService).enqueue(eq(STAGE_TOPIC), captor.capture());
        assertEquals(10L, captor.getValue().getStageBuildID());
        assertEquals(BuildStatus.FAILURE, captor.getValue().getBuildStatus());
        assertEquals(
            BusinessMessageUUID.stage(10L, 0, BuildStatus.FAILURE),
            captor.getValue().getMessageUUID());
    }

    @Test
    void doesNotChangeStageWhenJobUpdateFails() {
        JobBuildDto jobBuild =
            new JobBuildDto()
                .setJobBuildID(50L)
                .setJobConfigID(60L)
                .setStageBuildID(10L)
                .setStatus(BuildStatus.RUNNING);
        TriggerJobMessage message =
            new TriggerJobMessage()
                .setMessageUUID("job-failure")
                .setJobBuildID(50L)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.FAILURE);
        when(jobBuildService.getJobBuildByID(50L)).thenReturn(jobBuild);
        when(jobBuildService.updateJobBuildStatus(50L, BuildStatus.FAILURE, 0)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> messageCenter.onJobMessage(message));

        verify(stageBuildService).lockStageBuild(10L, 0);
        verify(stageBuildService, never())
            .transitionStageBuildStatus(
                eq(10L), eq(BuildStatus.RUNNING), eq(BuildStatus.FAILURE), eq(0));
        verify(outboxService, never()).enqueue(eq(STAGE_TOPIC), any());
    }

    @Test
    void doesNotChangePipelineWhenStageUpdateFails() {
        StageBuildDto stageBuild =
            new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        TriggerStageMessage message =
            new TriggerStageMessage()
                .setMessageUUID("stage-failure")
                .setStageBuildID(10L)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.FAILURE);
        when(stageBuildService.getStageBuildByID(10L)).thenReturn(stageBuild);
        when(stageBuildService.updateStageBuildStatusByID(BuildStatus.FAILURE, 10L, 0))
            .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> messageCenter.onStageMessage(message));

        verify(stageConfigService, never()).getStageConfigByID(20L);
        verify(outboxService, never()).enqueue(eq(PIPELINE_TOPIC), any());
    }

    @Test
    void rejectsPipelineMessageWhenPipelineUpdateFails() {
        TriggerPipelineMessage message =
            new TriggerPipelineMessage()
                .setMessageUUID("pipeline-failure")
                .setPipelineBuildID(30L)
                .setPipelineID(40L)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.FAILURE);
        when(pipelineBuildService.updatePipelineBuildStatus(30L, BuildStatus.FAILURE, 0))
            .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> messageCenter.onPipelineMessage(message));
    }

    private void assertPluginTerminalStatusPropagates(BuildStatus status) {
        TriggerPluginMessage message = pluginMessage(status);
        when(pluginBuildService.getJobBuildID(70L)).thenReturn(50L);
        when(pluginBuildService.updatePluginBuild(70L, status, 0)).thenReturn(true);

        messageCenter.onPluginMessage(message);

        ArgumentCaptor<TriggerJobMessage> captor = ArgumentCaptor.forClass(TriggerJobMessage.class);
        verify(outboxService).enqueue(eq(JOB_TOPIC), captor.capture());
        assertEquals(50L, captor.getValue().getJobBuildID());
        assertEquals(status, captor.getValue().getBuildStatus());
        assertEquals(BusinessMessageUUID.job(50L, 0, status), captor.getValue().getMessageUUID());
    }

    private TriggerPluginMessage pluginMessage(BuildStatus status) {
        return new TriggerPluginMessage()
            .setMessageUUID(BusinessMessageUUID.plugin(PluginType.TEXT, 70L, 0, status))
            .setPluginType(PluginType.TEXT)
            .setPluginBuildID(70L)
            .setExecutionAttempt(0)
            .setStatus(status);
    }

    private TriggerJobMessage prepareTailJobSuccess() {
        JobBuildDto jobBuild =
            new JobBuildDto()
                .setJobBuildID(50L)
                .setJobConfigID(60L)
                .setStageBuildID(10L)
                .setStatus(BuildStatus.RUNNING);
        StageBuildDto stageBuild =
            new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        StageConfigDto stageConfig = new StageConfigDto().setId(20L).setPipelineID(40L);
        JobRelationDto tailRelation =
            new JobRelationDto().setStageID(20L).setJobID(60L).setNextJobID(0L);
        List<JobBuildDto> tailJobs =
            List.of(
                new JobBuildDto()
                    .setJobBuildID(50L)
                    .setJobConfigID(60L)
                    .setStageBuildID(10L)
                    .setStatus(BuildStatus.SUCCESS));

        when(jobBuildService.getJobBuildByID(50L)).thenReturn(jobBuild);
        when(jobBuildService.updateJobBuildStatus(50L, BuildStatus.SUCCESS, 0)).thenReturn(true);
        when(stageBuildService.lockStageBuild(10L, 0)).thenReturn(stageBuild);
        when(stageConfigService.getStageConfigByID(20L)).thenReturn(stageConfig);
        when(jobRelationService.getNextJobRelation(20L, 60L)).thenReturn(tailRelation);
        when(jobBuildService.getTailJobBuildsForUpdate(20L, 10L)).thenReturn(tailJobs);
        when(jobBuildService.calculateStageStatus(tailJobs)).thenReturn(BuildStatus.SUCCESS);

        return new TriggerJobMessage()
            .setMessageUUID("tail-job-success")
            .setJobBuildID(50L)
            .setExecutionAttempt(0)
            .setBuildStatus(BuildStatus.SUCCESS);
    }
}
