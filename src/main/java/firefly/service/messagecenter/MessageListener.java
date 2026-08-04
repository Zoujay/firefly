package firefly.service.messagecenter;

import firefly.constant.MessageCategory;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

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
    private KafkaMessageVirtualThreadProcessor virtualThreadProcessor;

    @KafkaListener(topics = PIPELINE_TOPIC)
    public void onPipelineMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.savePipelineMessages(messages);
        logPersistenceResult(MessageCategory.PIPELINE, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(saveResult.newMessages(), MessageCategory.PIPELINE);
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.saveStageMessages(messages);
        logPersistenceResult(MessageCategory.STAGE, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(saveResult.newMessages(), MessageCategory.STAGE);
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.saveJobMessages(messages);
        logPersistenceResult(MessageCategory.JOB, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(saveResult.newMessages(), MessageCategory.JOB);
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        KafkaMessageSaveResult saveResult = kafkaMessageStore.savePluginMessages(messages);
        logPersistenceResult(MessageCategory.PLUGIN, messages.size(), saveResult);
        ack.acknowledge();
        processMessages(saveResult.newMessages(), MessageCategory.PLUGIN);
    }

    private void processMessages(
            List<ConsumerRecord<String, String>> messages,
            MessageCategory messageCategory
    ) {
        if (messages.isEmpty()) {
            return;
        }
        /*
         * Inbox 已经在 ACK 前提交。ACK 之后，每条新消息在独立虚拟线程中处理，
         * 并由处理器限制数据库业务并发。成功转为 SUCCESS，异常转为 FAILURE；
         * 宕机遗留的 ARCHIVED/PROCESSING 仍通过管理接口人工恢复，不扫描数据库。
         */
        virtualThreadProcessor.processAndWait(messages, messageCategory);
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
