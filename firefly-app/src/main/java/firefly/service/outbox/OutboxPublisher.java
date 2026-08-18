package firefly.service.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OutboxPublisher {

    @Autowired private OutboxStateService stateService;

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired private ObjectMapper objectMapper;

    /**
     * Performs exactly one publication attempt; there is deliberately no loop, scheduler, or
     * database poller. Normal writes call this once after commit, while PENDING/FAILED records are
     * retried through the manual administration endpoint.
     *
     * <p>State transitions are PENDING/FAILED -> PUBLISHING -> SENT or FAILED. A process crash
     * while sending leaves PUBLISHING, which an operator first resets to FAILED after checking
     * Kafka, then explicitly retries.
     */
    public boolean publishOnce(Long outboxID) {
        String publisherID = UUID.randomUUID().toString();
        Optional<OutboxPublishTask> claimed = stateService.claim(outboxID, publisherID);
        if (claimed.isEmpty()) {
            return false;
        }

        OutboxPublishTask task = claimed.get();
        try {
            JsonNode payload = objectMapper.readTree(task.payload());
            kafkaTemplate.send(task.topic(), task.messageKey(), payload).get(10, TimeUnit.SECONDS);
            if (!stateService.markSent(task.id(), task.publisherID())) {
                log.error(
                        "Kafka sent Outbox event {}, but PUBLISHING -> SENT "
                                + "did not match; manual verification is required",
                        task.id());
                return false;
            }
            return true;
        } catch (Exception exception) {
            stateService.markFailed(task.id(), task.publisherID(), exception);
            log.error(
                    "Failed to publish Outbox event {}; manual retry is required",
                    task.id(),
                    exception);
            return false;
        }
    }
}
