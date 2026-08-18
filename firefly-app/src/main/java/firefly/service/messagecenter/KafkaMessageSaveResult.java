package firefly.service.messagecenter;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.List;

public record KafkaMessageSaveResult(
        List<ConsumerRecord<String, String>> newMessages, int duplicateCount) {

    public KafkaMessageSaveResult {
        newMessages = List.copyOf(newMessages);
    }
}
