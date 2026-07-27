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
}
