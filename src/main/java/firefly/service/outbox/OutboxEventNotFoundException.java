package firefly.service.outbox;

public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(Long outboxID) {
        super("Outbox event not found: " + outboxID);
    }
}
