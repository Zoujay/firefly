package firefly.github.message;

import firefly.bean.dto.message.KafkaBusinessMessage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubWebhookMessage implements KafkaBusinessMessage {
    private String messageUUID;
    private String deliveryId;
}
