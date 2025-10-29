package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;
import firefly.constant.TriggerOrigin;
import firefly.model.trigger.BaseTriggerEntity;

public interface ITrigger<BaseT extends BaseTriggerEntity, BaseM extends BaseMessage> {

    TriggerOrigin getTriggerType();

    Long saveRealTrigger(BaseMessage triggerMessage);

    BaseMessage parseMessage(String messageRaw);


    void execute(BaseMessage message);

}
