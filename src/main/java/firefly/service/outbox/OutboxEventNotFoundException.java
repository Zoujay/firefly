package firefly.service.outbox;

public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(String outboxID) {
        super("Outbox event not found: " + outboxID);
    }
}
