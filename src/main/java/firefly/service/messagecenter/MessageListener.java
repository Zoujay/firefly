package firefly.service.messagecenter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
@RequiredArgsConstructor
public class MessageListener {

    private final KafkaMessageStore kafkaMessageStore;

    @KafkaListener(topics = PIPELINE_TOPIC)
    public void onPipelineMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.savePipelineMessages(messages);
        log.info("Persisted pipeline message batch of {}", messages.size());
        ack.acknowledge();
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.saveStageMessages(messages);
        log.info("Persisted stage message batch of {}", messages.size());
        ack.acknowledge();
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.saveJobMessages(messages);
        log.info("Persisted job message batch of {}", messages.size());
        ack.acknowledge();
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
        kafkaMessageStore.savePluginMessages(messages);
        log.info("Persisted plugin message batch of {}", messages.size());
        ack.acknowledge();
    }


}
