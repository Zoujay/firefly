package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;
import firefly.constant.TriggerOrigin;

public interface ITrigger<M extends BaseMessage> {

    TriggerOrigin getTriggerOrigin();

    Class<M> getMessageType();

    void dispatch(BaseMessage message);
}
