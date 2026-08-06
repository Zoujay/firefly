package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;
import firefly.constant.TriggerOrigin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class TriggerCenter implements ITriggerCenter {

    private final Map<TriggerOrigin, ITrigger<? extends BaseMessage>>
            triggerMap;

    @Autowired
    public TriggerCenter(List<ITrigger<? extends BaseMessage>> triggers) {
        EnumMap<TriggerOrigin, ITrigger<? extends BaseMessage>> map =
                new EnumMap<>(TriggerOrigin.class);

        for (ITrigger<? extends BaseMessage> trigger : triggers) {
            TriggerOrigin origin = trigger.getTriggerOrigin();
            if (origin == null) {
                throw new IllegalStateException(
                        "Trigger origin must not be null: "
                                + trigger.getClass().getName()
                );
            }

            ITrigger<? extends BaseMessage> existing =
                    map.putIfAbsent(origin, trigger);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate Trigger implementation for "
                                + origin
                                + ": "
                                + existing.getClass().getName()
                                + " and "
                                + trigger.getClass().getName()
                );
            }
        }
        this.triggerMap = Map.copyOf(map);
    }

    @Override
    public void dispatch(BaseMessage message) {
        if (message == null || message.getTriggerOrigin() == null) {
            throw new IllegalArgumentException(
                    "Trigger message and origin must not be null"
            );
        }

        TriggerOrigin triggerOrigin = message.getTriggerOrigin();
        ITrigger<? extends BaseMessage> trigger =
                triggerMap.get(triggerOrigin);
        if (trigger == null) {
            throw new IllegalStateException(
                    "Unsupported Trigger origin: " + triggerOrigin
            );
        }
        trigger.dispatch(message);
    }
}
