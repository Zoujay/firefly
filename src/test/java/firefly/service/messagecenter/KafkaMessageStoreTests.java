package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.dao.message.IJobMessageDao;
import firefly.dao.message.IPipelineMessageDao;
import firefly.dao.message.IPluginMessageDao;
import firefly.dao.message.IStageMessageDao;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaMessageStoreTests {

    @Mock
    private IPipelineMessageDao pipelineMessageDao;

    @Mock
    private IStageMessageDao stageMessageDao;

    @Mock
    private IJobMessageDao jobMessageDao;

    @Mock
    private IPluginMessageDao pluginMessageDao;

    @InjectMocks
    private KafkaMessageStore kafkaMessageStore;

    @Test
    void savesKafkaMetadataAndPayloadWithJpa() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, 0, BuildStatus.RUNNING);
        String payload = "{\"messageUUID\":\"" + messageUUID + "\"}";
        ConsumerRecord<String, String> message = new ConsumerRecord<>(
                "pipeline_message",
                2,
                42L,
                "pipeline-key",
                payload
        );
        when(pipelineMessageDao.insertIfAbsent(
                messageUUID,
                "pipeline_message",
                2,
                42L,
                "pipeline-key",
                payload
        )).thenReturn(1);

        KafkaMessageSaveResult result = kafkaMessageStore.savePipelineMessages(List.of(message));

        verify(pipelineMessageDao).insertIfAbsent(
                messageUUID,
                "pipeline_message",
                2,
                42L,
                "pipeline-key",
                payload
        );
        assertEquals(List.of(message), result.newMessages());
        assertEquals(0, result.duplicateCount());
    }

    @Test
    void usesDedicatedJpaRepositoryForEveryMessageType() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, 0, BuildStatus.RUNNING);
        ConsumerRecord<String, String> message = record("topic", 0, 1L, messageUUID);
        String payload = message.value();
        when(stageMessageDao.insertIfAbsent(messageUUID, "topic", 0, 1L, messageUUID, payload))
                .thenReturn(1);
        when(jobMessageDao.insertIfAbsent(messageUUID, "topic", 0, 1L, messageUUID, payload))
                .thenReturn(1);
        when(pluginMessageDao.insertIfAbsent(messageUUID, "topic", 0, 1L, messageUUID, payload))
                .thenReturn(1);

        kafkaMessageStore.saveStageMessages(List.of(message));
        kafkaMessageStore.saveJobMessages(List.of(message));
        kafkaMessageStore.savePluginMessages(List.of(message));

        verify(stageMessageDao).insertIfAbsent(messageUUID, "topic", 0, 1L, messageUUID, payload);
        verify(jobMessageDao).insertIfAbsent(messageUUID, "topic", 0, 1L, messageUUID, payload);
        verify(pluginMessageDao).insertIfAbsent(messageUUID, "topic", 0, 1L, messageUUID, payload);
    }

    @Test
    void skipsJpaForEmptyBatch() {
        kafkaMessageStore.savePipelineMessages(List.of());

        verifyNoInteractions(
                pipelineMessageDao,
                stageMessageDao,
                jobMessageDao,
                pluginMessageDao
        );
    }

    @Test
    void rejectsMessageWithoutBusinessUUID() {
        ConsumerRecord<String, String> message =
                new ConsumerRecord<>("pipeline_message", 0, 1L, null, "{}");

        assertThrows(
                IllegalArgumentException.class,
                () -> kafkaMessageStore.savePipelineMessages(List.of(message))
        );

        verifyNoInteractions(
                pipelineMessageDao,
                stageMessageDao,
                jobMessageDao,
                pluginMessageDao
        );
    }

    @Test
    void doesNotSaveBusinessUUIDThatAlreadyExists() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, 0, BuildStatus.RUNNING);
        ConsumerRecord<String, String> message = record("pipeline_message", 0, 2L, messageUUID);
        when(pipelineMessageDao.insertIfAbsent(
                messageUUID,
                message.topic(),
                message.partition(),
                message.offset(),
                message.key(),
                message.value()
        )).thenReturn(0);

        KafkaMessageSaveResult result = kafkaMessageStore.savePipelineMessages(List.of(message));

        assertEquals(List.of(), result.newMessages());
        assertEquals(1, result.duplicateCount());
    }

    @Test
    void removesDuplicateBusinessUUIDsWithinTheSameBatch() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, 0, BuildStatus.RUNNING);
        ConsumerRecord<String, String> first = record("pipeline_message", 0, 1L, messageUUID);
        ConsumerRecord<String, String> second = record("pipeline_message", 0, 2L, messageUUID);
        when(pipelineMessageDao.insertIfAbsent(
                messageUUID,
                first.topic(),
                first.partition(),
                first.offset(),
                first.key(),
                first.value()
        )).thenReturn(1);

        KafkaMessageSaveResult result =
                kafkaMessageStore.savePipelineMessages(List.of(first, second));

        verify(pipelineMessageDao, times(1)).insertIfAbsent(
                messageUUID,
                first.topic(),
                first.partition(),
                first.offset(),
                first.key(),
                first.value()
        );
        assertEquals(List.of(first), result.newMessages());
        assertEquals(1, result.duplicateCount());
    }

    @Test
    void returnsOnlyNewMessagesFromMixedBatch() {
        String duplicateUUID = BusinessMessageUUID.pipeline(11L, 0, BuildStatus.RUNNING);
        String newUUID = BusinessMessageUUID.pipeline(12L, 0, BuildStatus.RUNNING);
        ConsumerRecord<String, String> duplicate =
                record("pipeline_message", 0, 1L, duplicateUUID);
        ConsumerRecord<String, String> newMessage =
                record("pipeline_message", 0, 2L, newUUID);
        when(pipelineMessageDao.insertIfAbsent(
                duplicateUUID,
                duplicate.topic(),
                duplicate.partition(),
                duplicate.offset(),
                duplicate.key(),
                duplicate.value()
        )).thenReturn(0);
        when(pipelineMessageDao.insertIfAbsent(
                newUUID,
                newMessage.topic(),
                newMessage.partition(),
                newMessage.offset(),
                newMessage.key(),
                newMessage.value()
        )).thenReturn(1);

        KafkaMessageSaveResult result =
                kafkaMessageStore.savePipelineMessages(List.of(duplicate, newMessage));

        assertEquals(List.of(newMessage), result.newMessages());
        assertEquals(1, result.duplicateCount());
    }

    private ConsumerRecord<String, String> record(
            String topic,
            int partition,
            long offset,
            String messageUUID
    ) {
        return new ConsumerRecord<>(
                topic,
                partition,
                offset,
                messageUUID,
                "{\"messageUUID\":\"" + messageUUID + "\"}"
        );
    }
}
