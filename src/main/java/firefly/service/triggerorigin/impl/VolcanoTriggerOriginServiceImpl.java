package firefly.service.triggerorigin.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.BaseTriggerOriginDto;
import firefly.bean.dto.PipelineBuildDto;
import firefly.bean.dto.VolcanoTriggerDto;
import firefly.bean.dto.message.BaseMessage;
import firefly.bean.dto.message.VolcanoMessageEntity;
import firefly.constant.TriggerOrigin;
import firefly.dao.trigger.IVolcanoDao;
import firefly.model.origin.VolcanoEngine;
import firefly.service.triggerorigin.ITriggerOrigin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
@Transactional
public class VolcanoTriggerOriginServiceImpl implements ITriggerOrigin {

    @Autowired
    private IVolcanoDao volcanoDao;

    @Override
    public TriggerOrigin getTriggerOrigin() {
        return TriggerOrigin.VOLCANO;
    }

    @Override
    public BaseTriggerOriginDto parseTriggerOrigin(JsonNode triggerOrigin) {
        return new ObjectMapper().convertValue(triggerOrigin, VolcanoTriggerDto.class);
    }

    @Override
    public BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID) {
        VolcanoMessageEntity volcanoMessageEntity = new VolcanoMessageEntity();
        Long pipelineID = pipelineBuildDto.getPipelineID();
        Optional<VolcanoEngine> ve = volcanoDao.findByPipelineID(pipelineID);
        if (ve.isEmpty()) {
            return null;
        }
        VolcanoEngine volcanoConfig = ve.get();
        volcanoMessageEntity.setAk(volcanoConfig.getAk())
                .setSk(volcanoConfig.getSk())
                .setTriggerOrigin(TriggerOrigin.VOLCANO)
                .setPipelineBuildID(pipelineBuildID)
                .setPipelineID(pipelineID);
        return volcanoMessageEntity;
    }

    @Override
    public Long saveTriggerOrigin(JsonNode triggerOrigin, Long pipelineID) {
        VolcanoTriggerDto volcanoTriggerDto = (VolcanoTriggerDto)this.parseTriggerOrigin(triggerOrigin);
        VolcanoEngine volcanoEngine = this.assembleVolcanoEngine(volcanoTriggerDto, pipelineID);
        volcanoDao.save(volcanoEngine);
        return volcanoEngine.getId();
    }

    private VolcanoEngine assembleVolcanoEngine(VolcanoTriggerDto volcanoTriggerDto, Long pipelineID) {
        VolcanoEngine volcanoEngine = new  VolcanoEngine();
        volcanoEngine.setAk(volcanoTriggerDto.getAk())
                .setSk(volcanoTriggerDto.getSk())
                .setPipelineID(pipelineID);
        return volcanoEngine;
    }
}
