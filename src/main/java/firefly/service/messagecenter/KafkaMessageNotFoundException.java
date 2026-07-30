package firefly.service.messagecenter;

import firefly.constant.MessageCategory;

public class KafkaMessageNotFoundException extends RuntimeException {

    public KafkaMessageNotFoundException(
            MessageCategory category,
            String messageUUID
    ) {
        super("Kafka message not found: category="
                + category + ", messageUUID=" + messageUUID);
    }
}
