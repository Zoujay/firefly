package firefly.service.messagecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.message.KafkaBusinessMessage;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.MessageCategory;
import firefly.constant.PluginType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageListenerTests {

    @Mock
    private KafkaMessageStore kafkaMessageStore;

    @Mock
    private KafkaMessageProcessingCoordinator processingCoordinator;

    @Mock
    private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MessageListener messageListener;

    @org.junit.jupiter.api.BeforeEach
    void extractUUIDFromPayload() throws Exception {
        lenient().when(kafkaMessageStore.extractMessageUUID(any()))
                .thenAnswer(invocation -> objectMapper.readTree(
                                ((ConsumerRecord<?, ?>) invocation.getArgument(0))
                                        .value()
                                        .toString()
                        )
                        .get("messageUUID")
                        .asText());
    }

    @Test
    void persistsAcknowledgesAndDispatchesEveryPipelineMessage() throws Exception {
        TriggerPipelineMessage first = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        TriggerPipelineMessage second = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(2L)
                .setPipelineBuildID(20L)
                .setBuildStatus(BuildStatus.SUCCESS);
        List<ConsumerRecord<String, String>> messages = List.of(
                record("pipeline_message", 0, 10L, first),
                record("pipeline_message", 0, 11L, second)
        );
        when(kafkaMessageStore.savePipelineMessages(messages)).thenReturn(saved(messages));

        messageListener.onPipelineMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(
                kafkaMessageStore,
                acknowledgment
        );
        inOrder.verify(kafkaMessageStore).savePipelineMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PIPELINE,
                first.getMessageUUID()
        );
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PIPELINE,
                second.getMessageUUID()
        );
    }

    @Test
    void dispatchesBusinessProcessingOnVirtualThread() throws Exception {
        TriggerPipelineMessage pipelineMessage = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("pipeline_message", 0, 10L, pipelineMessage));
        AtomicReference<Thread> processingThread = new AtomicReference<>();
        when(kafkaMessageStore.savePipelineMessages(messages)).thenReturn(saved(messages));
        when(processingCoordinator.process(
                MessageCategory.PIPELINE,
                pipelineMessage.getMessageUUID()
        )).thenAnswer(invocation -> {
            processingThread.set(Thread.currentThread());
            return true;
        });

        messageListener.onPipelineMessage(messages, acknowledgment);

        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PIPELINE,
                pipelineMessage.getMessageUUID()
        );
        assertTrue(processingThread.get().isVirtual());
    }

    @Test
    void doesNotAcknowledgeOrDispatchWhenDatabaseSaveFails() throws Exception {
        TriggerPipelineMessage pipelineMessage = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("pipeline_message", 0, 10L, pipelineMessage));
        when(kafkaMessageStore.savePipelineMessages(messages))
                .thenThrow(new IllegalStateException("database failed"));

        assertThrows(
                IllegalStateException.class,
                () -> messageListener.onPipelineMessage(messages, acknowledgment)
        );

        verify(acknowledgment, never()).acknowledge();
        verify(processingCoordinator, never()).process(any(), any());
    }

    @Test
    void persistsAcknowledgesAndDispatchesStageMessages() throws Exception {
        TriggerStageMessage stageMessage = new TriggerStageMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setStageBuildID(20L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("stage_message", 1, 20L, stageMessage));
        when(kafkaMessageStore.saveStageMessages(messages)).thenReturn(saved(messages));

        messageListener.onStageMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(
                kafkaMessageStore,
                acknowledgment
        );
        inOrder.verify(kafkaMessageStore).saveStageMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.STAGE,
                stageMessage.getMessageUUID()
        );
    }

    @Test
    void persistsAcknowledgesAndDispatchesJobMessages() throws Exception {
        TriggerJobMessage jobMessage = new TriggerJobMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setJobBuildID(30L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("job_message", 2, 30L, jobMessage));
        when(kafkaMessageStore.saveJobMessages(messages)).thenReturn(saved(messages));

        messageListener.onJobMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(
                kafkaMessageStore,
                acknowledgment
        );
        inOrder.verify(kafkaMessageStore).saveJobMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.JOB,
                jobMessage.getMessageUUID()
        );
    }

    @Test
    void persistsAcknowledgesAndDispatchesPluginMessages() throws Exception {
        TriggerPluginMessage pluginMessage = new TriggerPluginMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPluginType(PluginType.TEXT)
                .setPluginBuildID(40L)
                .setStatus(BuildStatus.SUCCESS);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("plugin_message", 3, 40L, pluginMessage));
        when(kafkaMessageStore.savePluginMessages(messages)).thenReturn(saved(messages));

        messageListener.onPluginMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(
                kafkaMessageStore,
                acknowledgment
        );
        inOrder.verify(kafkaMessageStore).savePluginMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PLUGIN,
                pluginMessage.getMessageUUID()
        );
    }

    @Test
    void keepsTheBatchAcknowledgedAndContinuesWhenBusinessProcessingFails() throws Exception {
        TriggerPipelineMessage failed = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        TriggerPipelineMessage succeeded = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(2L)
                .setPipelineBuildID(20L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages = List.of(
                record("pipeline_message", 0, 10L, failed),
                record("pipeline_message", 0, 11L, succeeded)
        );
        when(kafkaMessageStore.savePipelineMessages(messages)).thenReturn(saved(messages));
        when(processingCoordinator.process(
                MessageCategory.PIPELINE,
                failed.getMessageUUID()
        )).thenReturn(false);

        assertDoesNotThrow(() -> messageListener.onPipelineMessage(messages, acknowledgment));

        verify(acknowledgment).acknowledge();
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PIPELINE,
                failed.getMessageUUID()
        );
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PIPELINE,
                succeeded.getMessageUUID()
        );
    }

    @Test
    void keepsTheBatchAcknowledgedWhenAnArchivedPayloadCannotBeParsed() {
        String messageUUID = UUID.randomUUID().toString();
        List<ConsumerRecord<String, String>> messages = List.of(
                new ConsumerRecord<>(
                        "pipeline_message",
                        0,
                        10L,
                        messageUUID,
                        "{not-valid-json"
                )
        );
        when(kafkaMessageStore.savePipelineMessages(messages)).thenReturn(saved(messages));

        assertDoesNotThrow(() -> messageListener.onPipelineMessage(messages, acknowledgment));

        verify(acknowledgment).acknowledge();
        verify(kafkaMessageStore, timeout(1000)).extractMessageUUID(messages.getFirst());
        verify(processingCoordinator, never()).process(any(), any());
    }

    @Test
    void acknowledgesDuplicateMessageWithoutDispatchingIt() throws Exception {
        TriggerPipelineMessage duplicate = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("pipeline_message", 0, 10L, duplicate));
        when(kafkaMessageStore.savePipelineMessages(messages))
                .thenReturn(new KafkaMessageSaveResult(List.of(), 1));

        messageListener.onPipelineMessage(messages, acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(processingCoordinator, never()).process(any(), any());
    }

    @Test
    void dispatchesOnlyNewMessagesFromMixedBatch() throws Exception {
        TriggerPipelineMessage duplicate = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        TriggerPipelineMessage newMessage = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(2L)
                .setPipelineBuildID(20L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages = List.of(
                record("pipeline_message", 0, 10L, duplicate),
                record("pipeline_message", 0, 11L, newMessage)
        );
        when(kafkaMessageStore.savePipelineMessages(messages))
                .thenReturn(new KafkaMessageSaveResult(List.of(messages.get(1)), 1));

        messageListener.onPipelineMessage(messages, acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(processingCoordinator, never()).process(
                MessageCategory.PIPELINE,
                duplicate.getMessageUUID()
        );
        verify(processingCoordinator, timeout(1000)).process(
                MessageCategory.PIPELINE,
                newMessage.getMessageUUID()
        );
    }

    private KafkaMessageSaveResult saved(List<ConsumerRecord<String, String>> messages) {
        return new KafkaMessageSaveResult(messages, 0);
    }

    private ConsumerRecord<String, String> record(
            String topic,
            int partition,
            long offset,
            KafkaBusinessMessage message
    ) throws JsonProcessingException {
        return new ConsumerRecord<>(
                topic,
                partition,
                offset,
                message.getMessageUUID(),
                objectMapper.writeValueAsString(message)
        );
    }
}
