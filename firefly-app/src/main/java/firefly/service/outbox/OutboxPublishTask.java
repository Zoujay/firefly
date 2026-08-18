package firefly.service.outbox;

public record OutboxPublishTask(
    Long id, String topic, String messageKey, String payload, String publisherID) {

}
