package firefly.service.messagecenter;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageListenerTests {

    @Mock
    private KafkaMessageStore kafkaMessageStore;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private MessageListener messageListener;

    @Test
    void acknowledgesPipelineBatchAfterDatabaseSave() {
        List<ConsumerRecord<String, String>> messages = List.of(
                record("pipeline_message", 0, 10L, "pipeline-1"),
                record("pipeline_message", 0, 11L, "pipeline-2")
        );

        messageListener.onPipelineMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment);
        inOrder.verify(kafkaMessageStore).savePipelineMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void doesNotAcknowledgeBatchWhenDatabaseSaveFails() {
        List<ConsumerRecord<String, String>> messages =
                List.of(record("pipeline_message", 0, 10L, "pipeline-1"));
        doThrow(new IllegalStateException("database failed"))
                .when(kafkaMessageStore).savePipelineMessages(messages);

        assertThrows(
                IllegalStateException.class,
                () -> messageListener.onPipelineMessage(messages, acknowledgment)
        );

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void acknowledgesStageBatchAfterDatabaseSave() {
        List<ConsumerRecord<String, String>> messages =
                List.of(record("stage_message", 1, 20L, "stage-1"));

        messageListener.onStageMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment);
        inOrder.verify(kafkaMessageStore).saveStageMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgesJobBatchAfterDatabaseSave() {
        List<ConsumerRecord<String, String>> messages =
                List.of(record("job_message", 2, 30L, "job-1"));

        messageListener.onJobMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment);
        inOrder.verify(kafkaMessageStore).saveJobMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgesPluginBatchAfterDatabaseSave() {
        List<ConsumerRecord<String, String>> messages =
                List.of(record("plugin_message", 3, 40L, "plugin-1"));

        messageListener.onPluginMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(kafkaMessageStore, acknowledgment);
        inOrder.verify(kafkaMessageStore).savePluginMessages(messages);
        inOrder.verify(acknowledgment).acknowledge();
    }

    private ConsumerRecord<String, String> record(String topic, int partition, long offset, String key) {
        String messageUUID = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return new ConsumerRecord<>(
                topic,
                partition,
                offset,
                messageUUID,
                "{\"messageUUID\":\"" + messageUUID + "\"}"
        );
    }
}
