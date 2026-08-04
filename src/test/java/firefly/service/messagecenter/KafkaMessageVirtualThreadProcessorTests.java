package firefly.service.messagecenter;

import firefly.constant.MessageCategory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaMessageVirtualThreadProcessorTests {

    private static final int MAX_CONCURRENCY = 24;

    @Mock
    private KafkaMessageStore kafkaMessageStore;

    @Mock
    private KafkaMessageProcessingCoordinator processingCoordinator;

    private KafkaMessageVirtualThreadProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new KafkaMessageVirtualThreadProcessor();
        ReflectionTestUtils.setField(
                processor,
                "kafkaMessageStore",
                kafkaMessageStore
        );
        ReflectionTestUtils.setField(
                processor,
                "processingCoordinator",
                processingCoordinator
        );
        ReflectionTestUtils.setField(
                processor,
                "maxConcurrency",
                MAX_CONCURRENCY
        );
        processor.initialize();
    }

    @AfterEach
    void tearDown() {
        processor.shutdown();
    }

    @Test
    void processesOnVirtualThreadsWithAtMostTwentyFourConcurrentTasks()
            throws Exception {
        int messageCount = MAX_CONCURRENCY + 1;
        List<ConsumerRecord<String, String>> messages = IntStream
                .range(0, messageCount)
                .mapToObj(this::record)
                .toList();
        AtomicInteger activeTasks = new AtomicInteger();
        AtomicInteger peakTasks = new AtomicInteger();
        AtomicBoolean onlyVirtualThreads = new AtomicBoolean(true);
        CountDownLatch firstWaveStarted = new CountDownLatch(MAX_CONCURRENCY);
        CountDownLatch releaseTasks = new CountDownLatch(1);

        when(kafkaMessageStore.extractMessageUUID(any()))
                .thenAnswer(invocation -> ((ConsumerRecord<?, ?>) invocation
                        .getArgument(0)).key());
        when(processingCoordinator.process(any(), any()))
                .thenAnswer(invocation -> {
                    onlyVirtualThreads.compareAndSet(
                            true,
                            Thread.currentThread().isVirtual()
                    );
                    int active = activeTasks.incrementAndGet();
                    peakTasks.accumulateAndGet(active, Math::max);
                    firstWaveStarted.countDown();
                    try {
                        releaseTasks.await();
                    } finally {
                        activeTasks.decrementAndGet();
                    }
                    return true;
                });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> batch = caller.submit(() -> processor.processAndWait(
                    messages,
                    MessageCategory.JOB
            ));

            assertTrue(firstWaveStarted.await(5, TimeUnit.SECONDS));
            assertEquals(MAX_CONCURRENCY, peakTasks.get());
            assertTrue(onlyVirtualThreads.get());

            releaseTasks.countDown();
            batch.get(10, TimeUnit.SECONDS);
        } finally {
            releaseTasks.countDown();
            caller.close();
        }

        verify(processingCoordinator, times(messageCount)).process(
                any(),
                any()
        );
    }

    @Test
    void isolatesOneMalformedMessageFromTheRestOfTheBatch() {
        List<ConsumerRecord<String, String>> messages = List.of(
                record(0),
                record(1)
        );
        when(kafkaMessageStore.extractMessageUUID(any()))
                .thenAnswer(invocation -> {
                    ConsumerRecord<?, ?> message = invocation.getArgument(0);
                    if (message.offset() == 0L) {
                        throw new IllegalArgumentException("invalid message");
                    }
                    return message.key();
                });

        assertDoesNotThrow(() -> processor.processAndWait(
                messages,
                MessageCategory.JOB
        ));

        verify(processingCoordinator).process(
                MessageCategory.JOB,
                "message-1"
        );
    }

    private ConsumerRecord<String, String> record(int offset) {
        return new ConsumerRecord<>(
                "job_message",
                0,
                offset,
                "message-" + offset,
                "{}"
        );
    }
}
