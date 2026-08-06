package firefly.service.pipelineconfig;

import firefly.bean.dto.PipelineConfigDto;
import firefly.constant.TriggerMatch;
import firefly.constant.TriggerModel;
import firefly.constant.TriggerOrigin;
import firefly.model.pipeline.PipelineModel;
import firefly.service.pipelineconfig.impl.PipelineConfigServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineConfigServiceImplTests {

    private final PipelineConfigServiceImpl pipelineConfigService = new PipelineConfigServiceImpl();

    @Test
    void assemblePipelineConfigDtoKeepsNameAndTriggerOriginInTheirOwnFields() {
        PipelineModel pipelineModel = new PipelineModel()
                .setId(1L)
                .setPipelineUUID("p".repeat(64))
                .setPipelineName("test-pipeline")
                .setTriggerMode(TriggerModel.MANUAL)
                .setTriggerMatch(TriggerMatch.ACCURATE)
                .setTriggerOrigin(TriggerOrigin.VOLCANO);

        PipelineConfigDto result = pipelineConfigService.assemblePipelineConfigDto(pipelineModel);

        assertAll(
                () -> assertEquals("test-pipeline", result.getName()),
                () -> assertEquals("VOLCANO", result.getTriggerOrigin())
        );
    }
}
