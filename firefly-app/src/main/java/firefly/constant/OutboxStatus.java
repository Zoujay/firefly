package firefly.constant;

/**
 * Publication state of an outbound Kafka event.
 *
 * <p>The state transition is deliberately explicit:</p>
 * <ol>
 *     <li>Business transaction: a new row starts at PENDING.</li>
 *     <li>Atomic publication claim: PENDING (or manually retried FAILURE)
 *     -> PUBLISHING.</li>
 *     <li>Kafka acknowledgement: PUBLISHING -> SENT.</li>
 *     <li>Send exception: PUBLISHING -> FAILURE. A crash can leave PUBLISHING,
 *     which an operator resets to FAILURE only after verification.</li>
 * </ol>
 * <p>SENT is terminal. PENDING/FAILED recovery is operator-triggered; no
 * background poller retries events.</p>
 */
public enum OutboxStatus {

    PENDING,

    PUBLISHING,

    SENT,

    FAILED
}
