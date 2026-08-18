package firefly.service.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import firefly.bean.dto.message.TriggerJobMessage;
import firefly.constant.BuildStatus;
import firefly.dao.jobbuild.IJobBuildDao;
import firefly.dao.jobconfig.IJobRelationDao;
import firefly.dao.outbox.IOutboxEventDao;
import firefly.dao.stagebuild.IStageBuildDao;
import firefly.dao.stageconfig.IStageConfigDao;
import firefly.model.job.JobBuild;
import firefly.model.job.JobRelation;
import firefly.model.stage.StageBuild;
import firefly.model.stage.StageModel;
import firefly.service.outbox.OutboxPublisher;
import firefly.support.FireflyIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@FireflyIntegrationTest
class StageJobCompletionConcurrencyIntegrationTests {

  @Autowired private MessageCenter messageCenter;

  @Autowired private IStageConfigDao stageConfigDao;

  @Autowired private IStageBuildDao stageBuildDao;

  @Autowired private IJobBuildDao jobBuildDao;

  @Autowired private IJobRelationDao jobRelationDao;

  @Autowired private IOutboxEventDao outboxEventDao;

  @MockitoBean private OutboxPublisher outboxPublisher;

  @Test
  void completesStageWhenParallelTailJobsFinishConcurrently() throws Exception {
    long pipelineID = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
    long pipelineBuildID = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
    long firstJobConfigID = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
    long secondJobConfigID = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);

    StageModel stageConfig =
        stageConfigDao.saveAndFlush(
            new StageModel()
                .setPipeline_id(pipelineID)
                .setStageOrder(0)
                .setStageUUID(UUID.randomUUID().toString())
                .setStageName("parallel-tail-stage"));
    StageBuild stageBuild =
        stageBuildDao.saveAndFlush(
            new StageBuild()
                .setPipelineBuildID(pipelineBuildID)
                .setStageID(stageConfig.getId())
                .setStageStatus(BuildStatus.RUNNING)
                .setExecutionAttempt(0));
    List<JobBuild> jobBuilds =
        jobBuildDao.saveAllAndFlush(
            List.of(
                new JobBuild()
                    .setJobID(firstJobConfigID)
                    .setStageBuildID(stageBuild.getId())
                    .setJobStatus(BuildStatus.RUNNING)
                    .setExecutionAttempt(0),
                new JobBuild()
                    .setJobID(secondJobConfigID)
                    .setStageBuildID(stageBuild.getId())
                    .setJobStatus(BuildStatus.RUNNING)
                    .setExecutionAttempt(0)));
    List<JobRelation> relations =
        jobRelationDao.saveAllAndFlush(
            List.of(
                tailRelation(pipelineID, stageConfig.getId(), firstJobConfigID),
                tailRelation(pipelineID, stageConfig.getId(), secondJobConfigID)));
    String stageSuccessMessageUUID =
        BusinessMessageUUID.stage(stageBuild.getId(), 0, BuildStatus.SUCCESS);

    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<Boolean> firstResult =
            executor.submit(() -> finishJob(jobBuilds.getFirst().getId(), ready, start));
        Future<Boolean> secondResult =
            executor.submit(() -> finishJob(jobBuilds.getLast().getId(), ready, start));

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(firstResult.get(10, TimeUnit.SECONDS));
        assertTrue(secondResult.get(10, TimeUnit.SECONDS));
      }

      assertEquals(
          BuildStatus.SUCCESS,
          stageBuildDao.findById(stageBuild.getId()).orElseThrow().getStageStatus());
      assertTrue(
          jobBuildDao.findAllById(jobBuilds.stream().map(JobBuild::getId).toList()).stream()
              .allMatch(jobBuild -> jobBuild.getJobStatus() == BuildStatus.SUCCESS));
      Long outboxID =
          outboxEventDao.findByMessageUUID(stageSuccessMessageUUID).orElseThrow().getId();
      verify(outboxPublisher, timeout(2_000).times(1)).publishOnce(outboxID);
    } finally {
      outboxEventDao.findByMessageUUID(stageSuccessMessageUUID).ifPresent(outboxEventDao::delete);
      jobRelationDao.deleteAll(relations);
      jobBuildDao.deleteAll(jobBuilds);
      stageBuildDao.deleteById(stageBuild.getId());
      stageConfigDao.deleteById(stageConfig.getId());
    }
  }

  private Boolean finishJob(Long jobBuildID, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertTrue(start.await(10, TimeUnit.SECONDS));
    return messageCenter.onJobMessage(
        new TriggerJobMessage()
            .setMessageUUID(BusinessMessageUUID.job(jobBuildID, 0, BuildStatus.SUCCESS))
            .setJobBuildID(jobBuildID)
            .setExecutionAttempt(0)
            .setBuildStatus(BuildStatus.SUCCESS));
  }

  private JobRelation tailRelation(Long pipelineID, Long stageConfigID, Long jobConfigID) {
    return new JobRelation()
        .setPipelineID(pipelineID)
        .setStageID(stageConfigID)
        .setJobID(jobConfigID)
        .setNextJobID(0L)
        .setPreviousJobID(0L)
        .setHeadJob(true);
  }
}
