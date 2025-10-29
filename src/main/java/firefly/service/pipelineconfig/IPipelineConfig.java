package firefly.service.pipelineconfig;

import firefly.bean.dto.PipelineConfigDto;
import firefly.bean.vo.request.PipelineConfigRequest;
import firefly.bean.vo.response.PipelineConfigResponse;
import firefly.bean.vo.response.StageConfigResponse;
import firefly.model.pipeline.PipelineModel;

import java.util.List;

public interface IPipelineConfig {
    String createPipeline(PipelineConfigRequest pipelineConfigRequest);

    PipelineConfigResponse getPipelineConfigByUUID(String pipelineUUID);

    PipelineConfigDto getPipelineConfigDtoByID(Long pipelineID);

    PipelineConfigDto assemblePipelineConfigDto(PipelineModel pipelineModel);

    PipelineConfigResponse assemblePipelineConfigResponse(PipelineConfigDto pipelineConfigDto, List<StageConfigResponse> stageConfigResponses);

    PipelineModel assemblePipelineModel(PipelineConfigRequest request, Long originID);
}
