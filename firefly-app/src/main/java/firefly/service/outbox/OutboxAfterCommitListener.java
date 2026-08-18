package firefly.service.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the normal one-time Kafka send only after the MySQL transaction has committed. This is not
 * a recovery poller: if the application stops before or during this callback, the durable Outbox
 * state is handled manually.
 */
@Component
public class OutboxAfterCommitListener {

  @Autowired private OutboxPublisher outboxPublisher;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(OutboxCreatedEvent event) {
    outboxPublisher.publishOnce(event.outboxID());
  }
}
