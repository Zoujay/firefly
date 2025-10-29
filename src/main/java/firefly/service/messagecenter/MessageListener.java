package firefly.service.messagecenter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

import static firefly.constant.KafkaConfiguration.*;

@Component
public class MessageListener {

    @Autowired
    private MessageCenter messageCenter;

    @KafkaListener(topics = PIPELINE_TOPIC)
    public void onPipelineMessage(String message, Acknowledgment ack) {
        System.out.println("message is " + message);
        Gson gson = new Gson();
        TriggerPipelineMessage triggerPipelineMessage = gson.fromJson(message, TriggerPipelineMessage.class);
        // modify pipeline status
        Boolean result = messageCenter.onPipelineMessage(triggerPipelineMessage);
        // send stage message
        // modify stage status
        if (result) {
            ack.acknowledge();
        }
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(String message, Acknowledgment ack) {
        System.out.println(message);
        Gson gson = new Gson();
        TriggerStageMessage triggerStageMessage = gson.fromJson(message, TriggerStageMessage.class);
        // modify pipeline status
        Boolean result = messageCenter.onStageMessage(triggerStageMessage);
        // send stage message
        // modify stage status
        if (result) {
            ack.acknowledge();
        }
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(String message, Acknowledgment ack) {
        System.out.println(message);
        Gson gson = new Gson();
        List<TriggerJobMessage> triggerJobMessages = gson.fromJson(message, new TypeToken<List<TriggerJobMessage>>() {
        }.getType());
        // modify pipeline status
        Boolean result = messageCenter.onJobMessages(triggerJobMessages);
        // send stage message
        // modify stage status
        if (result) {
            ack.acknowledge();
        }
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(String message, Acknowledgment ack) {
        System.out.println(message);
        Gson gson = new Gson();
        TriggerPluginMessage triggerPluginMessage = gson.fromJson(message, TriggerPluginMessage.class);
        // modify pipeline status
        Boolean result = messageCenter.onPluginMessage(triggerPluginMessage);
        // send stage message
        // modify stage status
        if (result) {
            ack.acknowledge();
        }
    }


}
