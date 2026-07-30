package firefly.service.outbox;

public record OutboxPublishTask(
        String id,
        String topic,
        String messageKey,
        String payload,
        String publisherID
) {
}
