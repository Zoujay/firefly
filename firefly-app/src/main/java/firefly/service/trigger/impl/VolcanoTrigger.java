package firefly.service.trigger.impl;

import firefly.bean.dto.message.VolcanoMessageEntity;
import firefly.constant.TriggerOrigin;
import firefly.dao.triggermessage.IVolcanoTriggerDao;
import firefly.model.trigger.VolcanoTriggerEntity;
import firefly.service.trigger.AbstractTrigger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VolcanoTrigger extends AbstractTrigger<VolcanoTriggerEntity, VolcanoMessageEntity> {

    @Autowired private IVolcanoTriggerDao volcanoTriggerDao;

    @Override
    public TriggerOrigin getTriggerOrigin() {
        return TriggerOrigin.VOLCANO;
    }

    @Override
    public Class<VolcanoMessageEntity> getMessageType() {
        return VolcanoMessageEntity.class;
    }

    @Override
    protected VolcanoTriggerEntity saveRealTrigger(VolcanoMessageEntity message) {
        VolcanoTriggerEntity triggerEntity = new VolcanoTriggerEntity();
        triggerEntity
                .setAk(message.getAk())
                .setSk(message.getSk())
                .setPipelineID(message.getPipelineID());
        return volcanoTriggerDao.save(triggerEntity);
    }
}
