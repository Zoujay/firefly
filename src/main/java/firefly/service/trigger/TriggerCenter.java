package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;
import firefly.constant.TriggerOrigin;
import firefly.model.trigger.BaseTriggerEntity;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TriggerCenter implements ITriggerCenter, InitializingBean {

    @Autowired
    private List<ITrigger<? extends BaseTriggerEntity, ? extends BaseMessage>> triggers;

    public static Map<TriggerOrigin, ITrigger<? extends BaseTriggerEntity, ? extends BaseMessage>> TRIGGER_MAP = new HashMap<>();

    @Override
    public void dispatch(BaseMessage message) {
        TriggerOrigin triggerOrigin = message.getTriggerOrigin();
        ITrigger<? extends BaseTriggerEntity, ? extends BaseMessage> trigger = TRIGGER_MAP.get(triggerOrigin);
        if (trigger == null) {
            System.out.println("trigger is null");
            return;
        }
        trigger.execute(message);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        for(ITrigger<? extends BaseTriggerEntity, ? extends BaseMessage> trigger : triggers) {
            TriggerOrigin triggerOrigin = trigger.getTriggerType();
            if(triggerOrigin == null) {
                continue;
            }
            TRIGGER_MAP.put(triggerOrigin, trigger);
        }
    }
}
