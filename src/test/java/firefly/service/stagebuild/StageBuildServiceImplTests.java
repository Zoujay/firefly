package firefly.service.stagebuild;

import firefly.bean.dto.StageBuildDto;
import firefly.constant.BuildStatus;
import firefly.dao.stagebuild.IStageBuildDao;
import firefly.model.stage.StageBuild;
import firefly.service.stagebuild.impl.StageBuildServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StageBuildServiceImplTests {

    @Mock
    private IStageBuildDao stageBuildDao;

    @InjectMocks
    private StageBuildServiceImpl stageBuildService;

    @Test
    void includesBuildIdWhenMappingEntityToDto() {
        StageBuild stageBuild = new StageBuild()
                .setId(10L)
                .setStageID(20L)
                .setPipelineBuildID(30L)
                .setStageStatus(BuildStatus.RUNNING);
        when(stageBuildDao.findById(10L)).thenReturn(Optional.of(stageBuild));

        StageBuildDto result = stageBuildService.getStageBuildByID(10L);

        assertEquals(10L, result.getStageBuildID());
        assertEquals(20L, result.getStageConfigID());
        assertEquals(30L, result.getPipelineBuildID());
        assertEquals(BuildStatus.RUNNING, result.getStatus());
    }

    @Test
    void scopesStageBuildLookupToPipelineBuild() {
        StageBuild stageBuild = new StageBuild()
                .setId(10L)
                .setStageID(20L)
                .setPipelineBuildID(30L)
                .setStageStatus(BuildStatus.PENDING);
        when(stageBuildDao.getStageBuildByStageConfigIDAndPipelineBuildID(20L, 30L))
                .thenReturn(Optional.of(stageBuild));

        StageBuildDto result =
                stageBuildService.getStageBuildByStageConfigIDAndPipelineBuildID(20L, 30L);

        verify(stageBuildDao).getStageBuildByStageConfigIDAndPipelineBuildID(20L, 30L);
        assertEquals(10L, result.getStageBuildID());
        assertEquals(20L, result.getStageConfigID());
        assertEquals(30L, result.getPipelineBuildID());
    }

    @Test
    void returnsTrueWhenAtomicStatusTransitionUpdatesTheStage() {
        when(stageBuildDao.transitionStageBuildStatus(
                10L,
                BuildStatus.RUNNING,
                BuildStatus.SUCCESS))
                .thenReturn(1);

        Boolean transitioned = stageBuildService.transitionStageBuildStatus(
                10L,
                BuildStatus.RUNNING,
                BuildStatus.SUCCESS);

        assertTrue(transitioned);
        verify(stageBuildDao).transitionStageBuildStatus(
                10L,
                BuildStatus.RUNNING,
                BuildStatus.SUCCESS);
    }

    @Test
    void returnsFalseWhenAnotherThreadAlreadyChangedTheStageStatus() {
        when(stageBuildDao.transitionStageBuildStatus(
                10L,
                BuildStatus.RUNNING,
                BuildStatus.SUCCESS))
                .thenReturn(0);

        Boolean transitioned = stageBuildService.transitionStageBuildStatus(
                10L,
                BuildStatus.RUNNING,
                BuildStatus.SUCCESS);

        assertFalse(transitioned);
    }
}
