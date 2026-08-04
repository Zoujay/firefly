package firefly.service.messagecenter;

import firefly.constant.MessageCategory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * Processes archived Kafka messages on virtual threads with bounded concurrency.
 *
 * <p>Virtual threads make blocking database work inexpensive from a JVM-thread
 * perspective, but they do not increase MySQL capacity. The semaphore therefore
 * limits active business processing while the listener waits for the current
 * batch to finish, which also prevents unbounded task accumulation.</p>
 */
@Slf4j
@Service
public class KafkaMessageVirtualThreadProcessor {

    @Autowired
    private KafkaMessageStore kafkaMessageStore;

    @Autowired
    private KafkaMessageProcessingCoordinator processingCoordinator;

    @Value("${firefly.kafka.processing.max-concurrency:24}")
    private int maxConcurrency = 24;

    private ExecutorService executor;

    private Semaphore concurrencyLimiter;

    @PostConstruct
    void initialize() {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException(
                    "firefly.kafka.processing.max-concurrency must be positive"
            );
        }
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("kafka-business-", 0)
                        .factory()
        );
        concurrencyLimiter = new Semaphore(maxConcurrency, true);
        log.info(
                "Kafka virtual-thread processor initialized: maxConcurrency={}",
                maxConcurrency
        );
    }

    public void processAndWait(
            List<ConsumerRecord<String, String>> messages,
            MessageCategory messageCategory
    ) {
        if (messages.isEmpty()) {
            return;
        }

        List<Future<?>> futures = new ArrayList<>(messages.size());
        for (ConsumerRecord<String, String> record : messages) {
            try {
                futures.add(executor.submit(
                        () -> processOne(record, messageCategory)
                ));
            } catch (RejectedExecutionException exception) {
                // The Inbox row is already durable and ACK has already happened.
                // An unsubmitted message stays ARCHIVED for explicit recovery.
                log.error(
                        "Virtual-thread submission rejected for archived {} message at {}-{}@{}; "
                                + "manual recovery is required",
                        messageCategory,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception
                );
            }
        }
        waitForCompletion(futures, messageCategory);
    }

    private void processOne(
            ConsumerRecord<String, String> record,
            MessageCategory messageCategory
    ) {
        boolean acquired = false;
        try {
            concurrencyLimiter.acquire();
            acquired = true;
            String messageUUID = kafkaMessageStore.extractMessageUUID(record);
            processingCoordinator.process(messageCategory, messageUUID);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Virtual thread interrupted for archived {} message at {}-{}@{}; "
                            + "manual recovery may be required",
                    messageCategory,
                    record.topic(),
                    record.partition(),
                    record.offset()
            );
        } catch (Exception exception) {
            log.error(
                    "Failed to process archived {} message at {}-{}@{}; manual recovery is required",
                    messageCategory,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception
            );
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
        }
    }

    private void waitForCompletion(
            List<Future<?>> futures,
            MessageCategory messageCategory
    ) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                futures.forEach(task -> task.cancel(true));
                log.warn(
                        "Interrupted while waiting for {} Kafka message batch; "
                                + "unfinished Inbox messages require manual recovery",
                        messageCategory
                );
                return;
            } catch (ExecutionException exception) {
                log.error(
                        "Unexpected virtual-thread failure while processing {} Kafka message batch",
                        messageCategory,
                        exception.getCause()
                );
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.close();
        }
    }
}
