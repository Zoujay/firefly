package firefly.service.messagecenter;

import com.google.gson.Gson;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

import static firefly.constant.KafkaConfiguration.*;

@Slf4j
@Component
public class MessageListener {

    @Autowired
    private MessageCenter messageCenter;

    private final Gson gson = new Gson();

    @KafkaListener(topics = PIPELINE_TOPIC)
    public void onPipelineMessage(List<String> messages, Acknowledgment ack) {
        for (String message : messages) {
            TriggerPipelineMessage triggerPipelineMessage = gson.fromJson(message, TriggerPipelineMessage.class);
            log.info("Processing pipeline message {}", triggerPipelineMessage.getMessageUUID());
            messageCenter.onPipelineMessage(triggerPipelineMessage);
        }
        ack.acknowledge();
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(List<String> messages, Acknowledgment ack) {
        for (String message : messages) {
            TriggerStageMessage triggerStageMessage = gson.fromJson(message, TriggerStageMessage.class);
            log.info("Processing stage message {}", triggerStageMessage.getMessageUUID());
            messageCenter.onStageMessage(triggerStageMessage);
        }
        ack.acknowledge();
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(List<String> messages, Acknowledgment ack) {
        for (String message : messages) {
            TriggerJobMessage triggerJobMessage = gson.fromJson(message, TriggerJobMessage.class);
            log.info("Processing job message {}", triggerJobMessage.getMessageUUID());
            messageCenter.onJobMessage(triggerJobMessage);
        }
        ack.acknowledge();
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(List<String> messages, Acknowledgment ack) {
        for (String message : messages) {
            TriggerPluginMessage triggerPluginMessage = gson.fromJson(message, TriggerPluginMessage.class);
            log.info("Processing plugin message {}", triggerPluginMessage.getMessageUUID());
            messageCenter.onPluginMessage(triggerPluginMessage);
        }
        ack.acknowledge();
    }


}
