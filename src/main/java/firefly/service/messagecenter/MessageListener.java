package firefly.service.messagecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.MessageCategory;
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
        KafkaMessageSaveResult saveResult = kafkaMessageStore.savePipelineMessages(messages);
        logPersistenceResult(MessageCategory.PIPELINE, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(
                saveResult.newMessages(),
                TriggerPipelineMessage.class,
                messageCenter::onPipelineMessage,
                MessageCategory.PIPELINE
        );
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.saveStageMessages(messages);
        logPersistenceResult(MessageCategory.STAGE, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(
                saveResult.newMessages(),
                TriggerStageMessage.class,
                messageCenter::onStageMessage,
                MessageCategory.STAGE
        );
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.saveJobMessages(messages);
        logPersistenceResult(MessageCategory.JOB, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(
                saveResult.newMessages(),
                TriggerJobMessage.class,
                messageCenter::onJobMessage,
                MessageCategory.JOB
        );
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.savePluginMessages(messages);
        logPersistenceResult(MessageCategory.PLUGIN, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(
                saveResult.newMessages(),
                TriggerPluginMessage.class,
                messageCenter::onPluginMessage,
                MessageCategory.PLUGIN
        );
    }

    private <T> void processMessages(
            List<ConsumerRecord<String, String>> messages,
            Class<T> messageType,
            Function<T, Boolean> handler,
            MessageCategory messageCategory
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

    private void logPersistenceResult(
            MessageCategory messageCategory,
            int receivedCount,
            KafkaMessageSaveResult saveResult
    ) {
        log.info(
                "Archived {} message batch: received={}, new={}, duplicate={}",
                messageCategory,
                receivedCount,
                saveResult.newMessages().size(),
                saveResult.duplicateCount()
        );
    }
}
