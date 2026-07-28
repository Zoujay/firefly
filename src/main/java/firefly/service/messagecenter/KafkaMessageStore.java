package firefly.service.messagecenter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Service
public class KafkaMessageStore {

    private static final int[] COLUMN_TYPES = {
            Types.VARCHAR,
            Types.VARCHAR,
            Types.INTEGER,
            Types.BIGINT,
            Types.VARCHAR,
            Types.LONGVARCHAR
    };

    private final JdbcTemplate jdbcTemplate;

    public KafkaMessageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void savePipelineMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages("pipeline_message", messages);
    }

    @Transactional
    public void saveStageMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages("stage_message", messages);
    }

    @Transactional
    public void saveJobMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages("job_message", messages);
    }

    @Transactional
    public void savePluginMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages("plugin_message", messages);
    }

    private void saveMessages(String tableName, List<ConsumerRecord<String, String>> messages) {
        if (messages.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO `%s`
                    (`message_uuid`, `topic`, `kafka_partition`, `kafka_offset`, `message_key`, `payload`)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE `id` = `id`
                """.formatted(tableName);

        List<Object[]> batchArguments = messages.stream()
                .map(message -> new Object[]{
                        extractMessageUUID(message),
                        message.topic(),
                        message.partition(),
                        message.offset(),
                        message.key(),
                        message.value()
                })
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArguments, COLUMN_TYPES);
    }

    private String extractMessageUUID(ConsumerRecord<String, String> message) {
        String payload = message.value();
        if (payload == null) {
            throw invalidMessage(message, "payload is null", null);
        }

        try {
            JsonElement root = JsonParser.parseString(payload);
            if (!root.isJsonObject()) {
                throw invalidMessage(message, "payload is not a JSON object", null);
            }
            JsonObject messageObject = root.getAsJsonObject();
            JsonElement messageUUID = messageObject.get("messageUUID");
            if (messageUUID == null || messageUUID.isJsonNull()) {
                throw invalidMessage(message, "messageUUID is missing", null);
            }

            String uuid = messageUUID.getAsString();
            UUID.fromString(uuid);
            return uuid;
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException exception) {
            throw invalidMessage(message, "payload or messageUUID is invalid", exception);
        } catch (IllegalArgumentException exception) {
            throw invalidMessage(message, "messageUUID is not a valid UUID", exception);
        }
    }

    private IllegalArgumentException invalidMessage(
            ConsumerRecord<String, String> message,
            String reason,
            Exception cause
    ) {
        String description = "Invalid Kafka business message at "
                + message.topic() + "-" + message.partition() + "@" + message.offset() + ": " + reason;
        return new IllegalArgumentException(description, cause);
    }
}
