package firefly.service.messagecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

import static firefly.constant.KafkaConfiguration.JOB_TOPIC;
import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static firefly.constant.KafkaConfiguration.PLUGIN_TOPIC;
import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;

@Slf4j
@Component
public class MessageListener {

    @Autowired
    private KafkaMessageStore kafkaMessageStore;

    @Autowired
    private MessageCenter messageCenter;

    @Autowired
    private ObjectMapper objectMapper;

    @KafkaListener(topics = PIPELINE_TOPIC)
    public void onPipelineMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.savePipelineMessages(messages);
        log.info("Persisted pipeline message batch of {}", messages.size());
        ack.acknowledge();
        processMessages(
                messages,
                TriggerPipelineMessage.class,
                messageCenter::onPipelineMessage,
                "pipeline"
        );
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.saveStageMessages(messages);
        log.info("Persisted stage message batch of {}", messages.size());
        ack.acknowledge();
        processMessages(
                messages,
                TriggerStageMessage.class,
                messageCenter::onStageMessage,
                "stage"
        );
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.saveJobMessages(messages);
        log.info("Persisted job message batch of {}", messages.size());
        ack.acknowledge();
        processMessages(
                messages,
                TriggerJobMessage.class,
                messageCenter::onJobMessage,
                "job"
        );
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.savePluginMessages(messages);
        log.info("Persisted plugin message batch of {}", messages.size());
        ack.acknowledge();
        processMessages(
                messages,
                TriggerPluginMessage.class,
                messageCenter::onPluginMessage,
                "plugin"
        );
    }

    private <T> void processMessages(
            List<ConsumerRecord<String, String>> messages,
            Class<T> messageType,
            Function<T, Boolean> handler,
            String messageCategory
    ) {
        for (ConsumerRecord<String, String> record : messages) {
            try {
                T message = objectMapper.readValue(record.value(), messageType);
                handler.apply(message);
            } catch (Exception exception) {
                log.error(
                        "Failed to process archived {} message at {}-{}@{}; manual recovery is required",
                        messageCategory,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception
                );
            }
        }
    }
}
