package firefly.service.messagecenter;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaMessageStoreTests {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Captor
    private ArgumentCaptor<List<Object[]>> argumentsCaptor;

    private KafkaMessageStore kafkaMessageStore;

    @BeforeEach
    void setUp() {
        kafkaMessageStore = new KafkaMessageStore(jdbcTemplate);
    }

    @Test
    void savesKafkaMetadataAndPayloadInPipelineTable() {
        String messageUUID = BusinessMessageUUID.pipeline(11L, firefly.constant.BuildStatus.RUNNING);
        ConsumerRecord<String, String> message = new ConsumerRecord<>(
                "pipeline_message",
                2,
                42L,
                "pipeline-key",
                "{\"messageUUID\":\"" + messageUUID + "\"}"
        );
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), any(int[].class)))
                .thenReturn(new int[]{1});

        kafkaMessageStore.savePipelineMessages(List.of(message));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), argumentsCaptor.capture(), any(int[].class));
        assertTrue(sqlCaptor.getValue().contains("`pipeline_message`"));
        assertTrue(sqlCaptor.getValue().contains("ON DUPLICATE KEY UPDATE"));
        assertArrayEquals(
                new Object[]{
                        messageUUID,
                        "pipeline_message",
                        2,
                        42L,
                        "pipeline-key",
                        "{\"messageUUID\":\"" + messageUUID + "\"}"
                },
                argumentsCaptor.getValue().getFirst()
        );
    }

    @Test
    void usesDedicatedTableForEveryMessageType() {
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), any(int[].class)))
                .thenReturn(new int[]{1});
        String messageUUID = BusinessMessageUUID.pipeline(11L, firefly.constant.BuildStatus.RUNNING);
        ConsumerRecord<String, String> message =
                new ConsumerRecord<>(
                        "topic",
                        0,
                        1L,
                        null,
                        "{\"messageUUID\":\"" + messageUUID + "\"}"
                );

        kafkaMessageStore.saveStageMessages(List.of(message));
        kafkaMessageStore.saveJobMessages(List.of(message));
        kafkaMessageStore.savePluginMessages(List.of(message));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(3))
                .batchUpdate(sqlCaptor.capture(), anyList(), any(int[].class));
        assertTrue(sqlCaptor.getAllValues().get(0).contains("`stage_message`"));
        assertTrue(sqlCaptor.getAllValues().get(1).contains("`job_message`"));
        assertTrue(sqlCaptor.getAllValues().get(2).contains("`plugin_message`"));
    }

    @Test
    void skipsDatabaseCallForEmptyBatch() {
        kafkaMessageStore.savePipelineMessages(List.of());

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), any(int[].class));
    }

    @Test
    void rejectsMessageWithoutBusinessUUID() {
        ConsumerRecord<String, String> message =
                new ConsumerRecord<>("pipeline_message", 0, 1L, null, "{}");

        assertThrows(
                IllegalArgumentException.class,
                () -> kafkaMessageStore.savePipelineMessages(List.of(message))
        );

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), any(int[].class));
    }
}
