package firefly.service.stageconfig.impl;

import firefly.bean.dto.StageConfigDto;
import firefly.bean.vo.request.StageConfigRequest;
import firefly.bean.vo.response.JobConfigResponse;
import firefly.bean.vo.response.StageConfigResponse;
import firefly.dao.stageconfig.IStageConfigDao;
import firefly.model.stage.StageModel;
import firefly.service.stageconfig.IStageConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class StageConfigServiceServiceImpl implements IStageConfigService {

    @Autowired
    private IStageConfigDao stageConfigDao;


    @Override
    public StageConfigDto createStage(StageConfigRequest stageConfigRequest, Long pipelineID) {
        StageModel model = this.assembleStageModel(stageConfigRequest, pipelineID);
        stageConfigDao.save(model);
        return this.assembleStageConfigDto(model);
    }

    @Override
    public StageConfigDto getStageConfigByUUID(String stageUUID) {
        return null;
    }

    @Override
    public StageConfigDto getStageConfigByID(Long stageConfigID) {
        Optional<StageModel> stageModel = stageConfigDao.findById(stageConfigID);
        if(stageModel.isEmpty()) {
            return null;
        }
        StageModel model = stageModel.get();
        return this.assembleStageConfigDto(model);
    }

    @Override
    public StageConfigDto assembleStageConfigDto(StageModel stageModel) {
        return new StageConfigDto(
                stageModel.getId(),
                stageModel.getPipeline_id(),
                stageModel.getStageUUID(),
                stageModel.getStageName()
        );
    }

    @Override
    public List<StageConfigDto> getStageConfigsByPipelineID(Long pipelineID) {
        List<StageModel> stages = stageConfigDao.getStageConfigByPipelineID(pipelineID);
        List<StageConfigDto> stageConfigDtos = new ArrayList<>();
        for (StageModel stageModel : stages) {
            stageConfigDtos.add(assembleStageConfigDto(stageModel));
        }
        return stageConfigDtos;
    }

    @Override
    public StageConfigResponse assembleConfigResponse(StageConfigDto stageConfigDto, List<JobConfigResponse> jobs) {
        StageConfigResponse  stageConfigResponse = new StageConfigResponse();
        stageConfigResponse.setId(stageConfigDto.getId());
        stageConfigResponse.setPipelineID(stageConfigDto.getPipelineID());
        stageConfigResponse.setName(stageConfigDto.getName());
        stageConfigResponse.setUuid(stageConfigDto.getUuid());
        stageConfigResponse.setJobs(jobs);
        return stageConfigResponse;
    }




    public StageModel assembleStageModel(StageConfigRequest request, Long pipelineID) {
        StageModel model = new StageModel();
        model.setPipeline_id(pipelineID);
        model.setStageUUID(request.getUuid());
        model.setStageName(request.getName());
        return model;
    }
}
