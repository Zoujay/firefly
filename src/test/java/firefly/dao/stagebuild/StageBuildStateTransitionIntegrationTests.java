package firefly.dao.stagebuild;

import firefly.constant.BuildStatus;
import firefly.model.stage.StageBuild;
import firefly.service.stagebuild.IStageBuildService;
import firefly.support.FireflyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FireflyIntegrationTest
class StageBuildStateTransitionIntegrationTests {

    @Autowired
    private IStageBuildDao stageBuildDao;

    @Autowired
    private IStageBuildService stageBuildService;

    @Test
    void allowsOnlyOneVirtualThreadToCompleteAStage() throws Exception {
        StageBuild stageBuild = stageBuildDao.saveAndFlush(new StageBuild()
                .setPipelineBuildID(9101L)
                .setStageID(9201L)
                .setStageStatus(BuildStatus.RUNNING));

        int taskCount = 20;
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return stageBuildService.transitionStageBuildStatus(
                            stageBuild.getId(),
                            BuildStatus.RUNNING,
                            BuildStatus.SUCCESS);
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            long successfulTransitions = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(10, TimeUnit.SECONDS))) {
                    successfulTransitions++;
                }
            }

            assertEquals(1L, successfulTransitions);
            assertEquals(
                    BuildStatus.SUCCESS,
                    stageBuildDao.findById(stageBuild.getId())
                            .orElseThrow()
                            .getStageStatus()
            );
        } finally {
            stageBuildDao.deleteById(stageBuild.getId());
        }
    }
}
