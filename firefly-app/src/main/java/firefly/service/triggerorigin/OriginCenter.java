package firefly.service.triggerorigin;

import firefly.constant.TriggerOrigin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OriginCenter implements InitializingBean {


    @Autowired
    private List<ITriggerOrigin> triggerOrigins;

    public static Map<TriggerOrigin, ITriggerOrigin> TriggerOriginMap = new HashMap<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        for (ITriggerOrigin triggerOrigin : triggerOrigins) {
            TriggerOriginMap.put(triggerOrigin.getTriggerOrigin(), triggerOrigin);
        }
    }
}
