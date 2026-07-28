package firefly.service.messagecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.message.KafkaBusinessMessage;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageListenerTests {

    @Mock
    private KafkaMessageStore kafkaMessageStore;

    @Mock
    private MessageCenter messageCenter;

    @Mock
    private Acknowledgment acknowledgment;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MessageListener messageListener;

    @Test
    void persistsAcknowledgesAndDispatchesEveryPipelineMessageInOrder() throws Exception {
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

        messageListener.onPipelineMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment, messageCenter);
        inOrder.verify(kafkaMessageStore).savePipelineMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        inOrder.verify(messageCenter).onPipelineMessage(first);
        inOrder.verify(messageCenter).onPipelineMessage(second);
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
        org.mockito.Mockito.doThrow(new IllegalStateException("database failed"))
                .when(kafkaMessageStore).savePipelineMessages(messages);

        assertThrows(
                IllegalStateException.class,
                () -> messageListener.onPipelineMessage(messages, acknowledgment)
        );

        verify(acknowledgment, never()).acknowledge();
        verify(messageCenter, never()).onPipelineMessage(any());
    }

    @Test
    void persistsAcknowledgesAndDispatchesStageMessages() throws Exception {
        TriggerStageMessage stageMessage = new TriggerStageMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setStageBuildID(20L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("stage_message", 1, 20L, stageMessage));

        messageListener.onStageMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment, messageCenter);
        inOrder.verify(kafkaMessageStore).saveStageMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        inOrder.verify(messageCenter).onStageMessage(stageMessage);
    }

    @Test
    void persistsAcknowledgesAndDispatchesJobMessages() throws Exception {
        TriggerJobMessage jobMessage = new TriggerJobMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setJobBuildID(30L)
                .setBuildStatus(BuildStatus.RUNNING);
        List<ConsumerRecord<String, String>> messages =
                List.of(record("job_message", 2, 30L, jobMessage));

        messageListener.onJobMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment, messageCenter);
        inOrder.verify(kafkaMessageStore).saveJobMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        inOrder.verify(messageCenter).onJobMessage(jobMessage);
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

        messageListener.onPluginMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment, messageCenter);
        inOrder.verify(kafkaMessageStore).savePluginMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
        inOrder.verify(messageCenter).onPluginMessage(pluginMessage);
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
        when(messageCenter.onPipelineMessage(failed))
                .thenThrow(new IllegalStateException("business processing failed"));

        assertDoesNotThrow(() -> messageListener.onPipelineMessage(messages, acknowledgment));

        verify(acknowledgment).acknowledge();
        verify(messageCenter).onPipelineMessage(failed);
        verify(messageCenter).onPipelineMessage(succeeded);
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

        assertDoesNotThrow(() -> messageListener.onPipelineMessage(messages, acknowledgment));

        verify(acknowledgment).acknowledge();
        verify(messageCenter, never()).onPipelineMessage(any());
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
