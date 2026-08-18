package firefly.dao.stageconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

import firefly.constant.BuildStatus;
import firefly.dao.stagebuild.IStageBuildDao;
import firefly.model.stage.StageBuild;
import firefly.model.stage.StageModel;
import firefly.support.FireflyIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@FireflyIntegrationTest
@Transactional
class StageOrderIntegrationTests {

    @Autowired
    private IStageConfigDao stageConfigDao;

    @Autowired
    private IStageBuildDao stageBuildDao;

    @Test
    void ordersStagesExplicitlyAndScopesBuildsToTheCurrentPipelineBuild() {
        StageModel secondStage =
            stageConfigDao.save(
                new StageModel()
                    .setPipeline_id(9001L)
                    .setStageOrder(1)
                    .setStageUUID("stage-order-integration-second")
                    .setStageName("second-stage"));
        StageModel firstStage =
            stageConfigDao.save(
                new StageModel()
                    .setPipeline_id(9001L)
                    .setStageOrder(0)
                    .setStageUUID("stage-order-integration-first")
                    .setStageName("first-stage"));

        StageBuild secondStageBuild =
            stageBuildDao.save(
                new StageBuild()
                    .setPipelineBuildID(9101L)
                    .setStageID(secondStage.getId())
                    .setStageStatus(BuildStatus.PENDING));
        StageBuild firstStageBuild =
            stageBuildDao.save(
                new StageBuild()
                    .setPipelineBuildID(9101L)
                    .setStageID(firstStage.getId())
                    .setStageStatus(BuildStatus.PENDING));

        List<StageModel> orderedStages = stageConfigDao.getStageConfigByPipelineID(9001L);
        List<StageBuild> orderedBuilds = stageBuildDao.getStageBuildByPipelineBuildID(9101L);
        StageBuild scopedBuild =
            stageBuildDao
                .getStageBuildByStageConfigIDAndPipelineBuildID(secondStage.getId(), 9101L)
                .orElseThrow();

        assertEquals(
            List.of(firstStage.getId(), secondStage.getId()),
            orderedStages.stream().map(StageModel::getId).toList());
        assertEquals(
            List.of(firstStageBuild.getId(), secondStageBuild.getId()),
            orderedBuilds.stream().map(StageBuild::getId).toList());
        assertEquals(secondStageBuild.getId(), scopedBuild.getId());
    }
}
