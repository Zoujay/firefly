package firefly.service.pipelinebuild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import firefly.bean.vo.response.PipelineRetryResponse;
import firefly.constant.BuildStatus;
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
import firefly.service.pipelinebuild.impl.PipelineBuildServiceImpl;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.impl.StageConfigServiceServiceImpl;
import firefly.service.trigger.TriggerCenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class PipelineBuildRetryTests {

    @Mock
    private IPipelineBuildDao pipelineBuildDao;
    @Mock
    private IStageBuildDao stageBuildDao;
    @Mock
    private IJobBuildDao jobBuildDao;
    @Mock
    private ITextPluginBuildDao textPluginBuildDao;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private IPipelineConfigService pipelineConfig;
    @Mock
    private IStageBuildService stageBuildService;
    @Mock
    private IJobConfigService jobConfig;
    @Mock
    private StageConfigServiceServiceImpl stageConfigService;
    @Mock
    private JobBuildServiceImpl jobBuildService;
    @Mock
    private TriggerCenter triggerCenter;

    @InjectMocks
    private PipelineBuildServiceImpl pipelineBuildService;

    @Test
    void reusesBuildRecordsAndResetsOnlyUnsuccessfulWork() {
        PipelineBuild pipeline =
            new PipelineBuild()
                .setId(1L)
                .setPipelineStatus(BuildStatus.RUNNING)
                .setExecutionAttempt(1);
        StageBuild successfulStage = stage(10L, BuildStatus.SUCCESS, 0);
        StageBuild failedStage = stage(11L, BuildStatus.FAILURE, 0);
        StageBuild pendingStage = stage(12L, BuildStatus.PENDING, 0);
        JobBuild successfulJob = job(101L, 11L, BuildStatus.SUCCESS, 0);
        JobBuild failedJob = job(102L, 11L, BuildStatus.FAILURE, 0);
        JobBuild pendingJob = job(103L, 12L, BuildStatus.PENDING, 0);
        TextPluginBuild failedPlugin = plugin(201L, 102L, BuildStatus.FAILURE, 0);
        TextPluginBuild pendingPlugin = plugin(202L, 103L, BuildStatus.PENDING, 0);

        when(pipelineBuildDao.claimRetry(1L, BuildStatus.FAILURE, BuildStatus.RUNNING))
            .thenReturn(1);
        when(pipelineBuildDao.findById(1L)).thenReturn(Optional.of(pipeline));
        when(stageBuildDao.getStageBuildByPipelineBuildID(1L))
            .thenReturn(List.of(successfulStage, failedStage, pendingStage));
        when(jobBuildDao.getJobBuildsByStageBuildID(11L))
            .thenReturn(List.of(successfulJob, failedJob));
        when(jobBuildDao.getJobBuildsByStageBuildID(12L)).thenReturn(List.of(pendingJob));
        when(textPluginBuildDao.findByJobBuildID(102L)).thenReturn(Optional.of(failedPlugin));
        when(textPluginBuildDao.findByJobBuildID(103L)).thenReturn(Optional.of(pendingPlugin));

        PipelineRetryResponse response = pipelineBuildService.retryPipeline(1L);

        assertEquals(1L, response.getPipelineBuildID());
        assertEquals(1, response.getExecutionAttempt());
        assertEquals(BuildStatus.SUCCESS, successfulStage.getStageStatus());
        assertEquals(0, successfulStage.getExecutionAttempt());
        assertEquals(BuildStatus.PENDING, failedStage.getStageStatus());
        assertEquals(1, failedStage.getExecutionAttempt());
        assertEquals(BuildStatus.PENDING, pendingStage.getStageStatus());
        assertEquals(1, pendingStage.getExecutionAttempt());
        assertEquals(BuildStatus.SUCCESS, successfulJob.getJobStatus());
        assertEquals(0, successfulJob.getExecutionAttempt());
        assertEquals(BuildStatus.PENDING, failedJob.getJobStatus());
        assertEquals(1, failedJob.getExecutionAttempt());
        assertEquals(BuildStatus.PENDING, pendingJob.getJobStatus());
        assertEquals(1, pendingJob.getExecutionAttempt());
        assertEquals(BuildStatus.PENDING, failedPlugin.getTextPluginStatus());
        assertEquals(1, failedPlugin.getExecutionAttempt());
        assertEquals(BuildStatus.PENDING, pendingPlugin.getTextPluginStatus());
        assertEquals(1, pendingPlugin.getExecutionAttempt());

        verify(textPluginBuildDao, never()).findByJobBuildID(101L);
        ArgumentCaptor<PipelineRetryPreparedEvent> eventCaptor =
            ArgumentCaptor.forClass(PipelineRetryPreparedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(11L, eventCaptor.getValue().stageBuildID());
        assertEquals(1, eventCaptor.getValue().executionAttempt());
    }

    @Test
    void rejectsRetryWhenAtomicClaimDoesNotWin() {
        when(pipelineBuildDao.claimRetry(1L, BuildStatus.FAILURE, BuildStatus.RUNNING))
            .thenReturn(0);

        assertThrows(
            PipelineRetryNotAllowedException.class,
            () -> pipelineBuildService.retryPipeline(1L));

        verify(pipelineBuildDao, never()).findById(1L);
        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    private StageBuild stage(Long id, BuildStatus status, Integer executionAttempt) {
        return new StageBuild()
            .setId(id)
            .setPipelineBuildID(1L)
            .setStageID(id + 100L)
            .setStageStatus(status)
            .setExecutionAttempt(executionAttempt);
    }

    private JobBuild job(Long id, Long stageBuildID, BuildStatus status, Integer executionAttempt) {
        return new JobBuild()
            .setId(id)
            .setStageBuildID(stageBuildID)
            .setJobID(id + 100L)
            .setJobStatus(status)
            .setExecutionAttempt(executionAttempt);
    }

    private TextPluginBuild plugin(
        Long id, Long jobBuildID, BuildStatus status, Integer executionAttempt) {
        return new TextPluginBuild()
            .setId(id)
            .setJobBuildID(jobBuildID)
            .setPluginID(id + 100L)
            .setTextPluginStatus(status)
            .setExecutionAttempt(executionAttempt);
    }
}
