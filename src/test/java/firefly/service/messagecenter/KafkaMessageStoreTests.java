package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.dao.message.IJobMessageDao;
import firefly.dao.message.IPipelineMessageDao;
import firefly.dao.message.IPluginMessageDao;
import firefly.dao.message.IStageMessageDao;
import firefly.model.message.PipelineMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
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

    @Captor
    private ArgumentCaptor<Iterable<PipelineMessage>> pipelineMessagesCaptor;

    @InjectMocks
    private KafkaMessageStore kafkaMessageStore;

    @Test
    void savesKafkaMetadataAndPayloadWithJpa() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, BuildStatus.RUNNING);
        String payload = "{\"messageUUID\":\"" + messageUUID + "\"}";
        ConsumerRecord<String, String> message = new ConsumerRecord<>(
                "pipeline_message",
                2,
                42L,
                "pipeline-key",
                payload
        );
        when(pipelineMessageDao.findExistingMessageUUIDs(anyList())).thenReturn(java.util.Set.of());

        kafkaMessageStore.savePipelineMessages(List.of(message));

        verify(pipelineMessageDao).saveAll(pipelineMessagesCaptor.capture());
        PipelineMessage savedMessage = pipelineMessagesCaptor.getValue().iterator().next();
        assertEquals(messageUUID, savedMessage.getMessageUUID());
        assertEquals("pipeline_message", savedMessage.getTopic());
        assertEquals(2, savedMessage.getKafkaPartition());
        assertEquals(42L, savedMessage.getKafkaOffset());
        assertEquals("pipeline-key", savedMessage.getMessageKey());
        assertEquals(payload, savedMessage.getPayload());
    }

    @Test
    void usesDedicatedJpaRepositoryForEveryMessageType() {
        when(stageMessageDao.findExistingMessageUUIDs(anyList())).thenReturn(java.util.Set.of());
        when(jobMessageDao.findExistingMessageUUIDs(anyList())).thenReturn(java.util.Set.of());
        when(pluginMessageDao.findExistingMessageUUIDs(anyList())).thenReturn(java.util.Set.of());
        String messageUUID = BusinessMessageUUID.pipeline(11L, BuildStatus.RUNNING);
        ConsumerRecord<String, String> message = record("topic", 0, 1L, messageUUID);

        kafkaMessageStore.saveStageMessages(List.of(message));
        kafkaMessageStore.saveJobMessages(List.of(message));
        kafkaMessageStore.savePluginMessages(List.of(message));

        verify(stageMessageDao).saveAll(anyList());
        verify(jobMessageDao).saveAll(anyList());
        verify(pluginMessageDao).saveAll(anyList());
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
        String messageUUID = BusinessMessageUUID.pipeline(11L, BuildStatus.RUNNING);
        PipelineMessage existingMessage = new PipelineMessage(
                messageUUID,
                "pipeline_message",
                0,
                1L,
                messageUUID,
                "{\"messageUUID\":\"" + messageUUID + "\"}"
        );
        when(pipelineMessageDao.findExistingMessageUUIDs(anyList()))
                .thenReturn(java.util.Set.of(existingMessage.getMessageUUID()));

        kafkaMessageStore.savePipelineMessages(
                List.of(record("pipeline_message", 0, 2L, messageUUID))
        );

        verify(pipelineMessageDao, never()).saveAll(anyList());
    }

    @Test
    void removesDuplicateBusinessUUIDsWithinTheSameBatch() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, BuildStatus.RUNNING);
        when(pipelineMessageDao.findExistingMessageUUIDs(anyList())).thenReturn(java.util.Set.of());

        kafkaMessageStore.savePipelineMessages(List.of(
                record("pipeline_message", 0, 1L, messageUUID),
                record("pipeline_message", 0, 2L, messageUUID)
        ));

        verify(pipelineMessageDao).saveAll(pipelineMessagesCaptor.capture());
        Iterable<PipelineMessage> savedMessages = pipelineMessagesCaptor.getValue();
        assertTrue(hasOnlyOne(savedMessages));
    }

    private boolean hasOnlyOne(Iterable<PipelineMessage> messages) {
        var iterator = messages.iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        iterator.next();
        return !iterator.hasNext();
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
