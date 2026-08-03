package firefly.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.message.KafkaBusinessMessage;
import firefly.dao.outbox.IOutboxEventDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes downstream Kafka events to the Outbox.
 *
 * <p>{@link Propagation#MANDATORY} means this method does not open a new
 * transaction: an existing transaction is required or Spring throws an
 * exception immediately. This is essential here because the business state
 * change and Outbox insert must commit or roll back together. REQUIRES_NEW or
 * no transaction would recreate the exact MySQL/Kafka consistency gap that
 * Outbox is intended to close.</p>
 */
@Service
public class OutboxService {

    @Autowired
    private IOutboxEventDao outboxEventDao;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, KafkaBusinessMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Cannot serialize Outbox message "
                            + message.getMessageUUID(),
                    exception
            );
        }

        int inserted = outboxEventDao.insertIfAbsent(
                message.getMessageUUID(),
                topic,
                BusinessMessageKey.from(message),
                message.getClass().getName(),
                payload
        );
        if (inserted == 1) {
            Long outboxID = outboxEventDao
                    .findIDByMessageUUID(message.getMessageUUID())
                    .orElseThrow(() -> new IllegalStateException(
                            "Outbox event was inserted but cannot be found: "
                                    + message.getMessageUUID()
                    ));
            /*
             * The event is published inside the current transaction. Its
             * listener runs AFTER_COMMIT, so a rollback produces neither an
             * Outbox row nor a Kafka send attempt.
             */
            applicationEventPublisher.publishEvent(
                    new OutboxCreatedEvent(outboxID)
            );
        }
    }
}
