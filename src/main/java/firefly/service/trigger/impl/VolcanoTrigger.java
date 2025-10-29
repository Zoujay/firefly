package firefly.service.trigger.impl;

import firefly.bean.dto.message.BaseMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.VolcanoMessageEntity;
import firefly.constant.BuildStatus;
import firefly.constant.KafkaConfiguration;
import firefly.constant.TriggerOrigin;
import firefly.dao.triggermessage.IVolcanoTriggerDao;
import firefly.model.trigger.VolcanoTriggerEntity;
import firefly.service.trigger.AbstractTrigger;
import firefly.service.trigger.ITrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class VolcanoTrigger extends AbstractTrigger<VolcanoTriggerEntity, VolcanoMessageEntity>
        implements ITrigger<VolcanoTriggerEntity, VolcanoMessageEntity> {

    @Autowired
    private IVolcanoTriggerDao volcanoTriggerDao;

    @Autowired
    private KafkaTemplate<String,Object> kafkaTemplate;

    @Override
    public TriggerOrigin getTriggerType() {
        return TriggerOrigin.VOLCANO;
    }

    @Override
    public BaseMessage parseMessage(String messageRaw) {
        return super.parseMessage(messageRaw);
    }

    @Override
    public Long saveRealTrigger(BaseMessage triggerMessage) {
        VolcanoTriggerEntity volcanoTriggerEntity = this.assembleVolcanoTriggerEntity((VolcanoMessageEntity) triggerMessage);
        volcanoTriggerDao.save(volcanoTriggerEntity);
        return volcanoTriggerEntity.getId();
    }


    @Override
    public void execute(BaseMessage message) {
        Long triggerID = saveRealTrigger(message);
        message.setTriggerID(triggerID);
        // trigger pipeline
        // send message to message center
        TriggerPipelineMessage triggerPipelineMessage = new TriggerPipelineMessage();
        UUID uuid = UUID.randomUUID();
        triggerPipelineMessage.setPipelineBuildID(message.getPipelineBuildID())
                .setMessageUUID(uuid.toString())
                .setPipelineID(message.getPipelineID())
                .setBuildStatus(BuildStatus.RUNNING);
        kafkaTemplate.send(KafkaConfiguration.PIPELINE_TOPIC, triggerPipelineMessage);

    }

    public VolcanoTriggerEntity assembleVolcanoTriggerEntity(VolcanoMessageEntity volcanoMessageEntity ) {
        VolcanoTriggerEntity volcanoTriggerEntity = new VolcanoTriggerEntity();
        volcanoTriggerEntity.setAk(volcanoMessageEntity.getAk())
                .setSk(volcanoMessageEntity.getSk())
                .setPipelineID(volcanoMessageEntity.getPipelineID());

        return volcanoTriggerEntity;
    }


}
