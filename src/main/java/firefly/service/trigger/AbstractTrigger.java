package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;
import firefly.constant.TriggerOrigin;
import firefly.model.trigger.BaseTriggerEntity;

public class AbstractTrigger<BaseT extends BaseTriggerEntity, BaseM extends BaseMessage> implements ITrigger<BaseT, BaseM> {

    @Override
    public TriggerOrigin getTriggerType() {
        return null;
    }

    @Override
    public Long saveRealTrigger(BaseMessage triggerMessage) {
        return 0L;
    }

    @Override
    public BaseMessage parseMessage(String messageRaw) {
        return null;
    }


    @Override
    public void execute(BaseMessage message) {

    }
}
