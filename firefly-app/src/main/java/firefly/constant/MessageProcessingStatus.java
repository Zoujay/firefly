package firefly.constant;

/**
 * Processing state of an inbound Kafka message stored in an Inbox table.
 *
 * <p>The state transition is deliberately explicit:</p>
 * <ol>
 *     <li>Kafka archive: new row starts at ARCHIVED.</li>
 *     <li>Atomic claim: ARCHIVED (or manually retried FAILURE) -> PROCESSING.</li>
 *     <li>Business commit: PROCESSING -> SUCCESS in the same transaction as
 *     business state changes and downstream Outbox inserts.</li>
 *     <li>Business rollback: PROCESSING -> FAILURE in a new transaction; an
 *     abandoned PROCESSING row can also be reset to FAILURE by an operator.</li>
 * </ol>
 * <p>SUCCESS is terminal. FAILURE can return to PROCESSING only through an
 * operator action; there is no database poller or automatic retry.</p>
 */
public enum MessageProcessingStatus {

    ARCHIVED,

    PROCESSING,

    SUCCESS,

    FAILURE
}
