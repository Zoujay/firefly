package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;

public interface ITriggerCenter {
    void dispatch(BaseMessage message);
}
