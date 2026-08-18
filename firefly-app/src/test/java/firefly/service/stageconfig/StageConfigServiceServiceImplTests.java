package firefly.service.stageconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

import firefly.bean.dto.StageConfigDto;
import firefly.dao.stageconfig.IStageConfigDao;
import firefly.model.stage.StageModel;
import firefly.service.stageconfig.impl.StageConfigServiceServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StageConfigServiceServiceImplTests {

    @Mock
    private IStageConfigDao stageConfigDao;

    @InjectMocks
    private StageConfigServiceServiceImpl stageConfigService;

    @Test
    void includesStageOrderWhenMappingEntityToDto() {
        StageModel stage =
            new StageModel()
                .setId(10L)
                .setPipeline_id(20L)
                .setStageUUID("stage-uuid")
                .setStageName("build")
                .setStageOrder(2);

        StageConfigDto result = stageConfigService.assembleStageConfigDto(stage);

        assertEquals(10L, result.getId());
        assertEquals(20L, result.getPipelineID());
        assertEquals(2, result.getStageOrder());
    }
}
