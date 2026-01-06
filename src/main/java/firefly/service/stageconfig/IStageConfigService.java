package firefly.service.stageconfig;


import firefly.bean.dto.StageConfigDto;
import firefly.bean.vo.request.StageConfigRequest;
import firefly.bean.vo.response.JobConfigResponse;
import firefly.bean.vo.response.StageConfigResponse;
import firefly.model.stage.StageModel;

import java.util.List;

public interface IStageConfigService {
    StageConfigDto createStage(StageConfigRequest pipelineConfigRequest, Long pipelineId);
    StageConfigDto getStageConfigByUUID(String stageUUID);
    StageConfigDto getStageConfigByID(Long stageConfigID);
    StageConfigDto assembleStageConfigDto(StageModel stageModel);
    List<StageConfigDto> getStageConfigsByPipelineID(Long pipelineID);
    StageConfigResponse assembleConfigResponse(StageConfigDto stageConfigDto, List<List<JobConfigResponse>> jobs);
    StageModel assembleStageModel(StageConfigRequest request, Long pipelineID);
}

