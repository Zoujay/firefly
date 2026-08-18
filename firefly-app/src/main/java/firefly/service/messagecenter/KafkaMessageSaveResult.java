package firefly.service.messagecenter;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public record KafkaMessageSaveResult(
    List<ConsumerRecord<String, String>> newMessages, int duplicateCount) {

  public KafkaMessageSaveResult {
    newMessages = List.copyOf(newMessages);
  }
}
