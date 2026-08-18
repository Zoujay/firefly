package firefly.constant;

/**
 * Publication state of an outbound Kafka event.
 *
 * <p>The state transition is deliberately explicit:
 *
 * <ol>
 *   <li>Business transaction: a new row starts at PENDING.
 *   <li>Atomic publication claim: PENDING (or manually retried FAILURE) -> PUBLISHING.
 *   <li>Kafka acknowledgement: PUBLISHING -> SENT.
 *   <li>Send exception: PUBLISHING -> FAILURE. A crash can leave PUBLISHING, which an operator
 *       resets to FAILURE only after verification.
 * </ol>
 *
 * <p>SENT is terminal. PENDING/FAILED recovery is operator-triggered; no background poller retries
 * events.
 */
public enum OutboxStatus {
  PENDING,

  PUBLISHING,

  SENT,

  FAILED
}
