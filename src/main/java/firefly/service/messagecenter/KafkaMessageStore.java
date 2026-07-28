package firefly.service.messagecenter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import firefly.dao.message.IJobMessageDao;
import firefly.dao.message.IKafkaMessageDao;
import firefly.dao.message.IPipelineMessageDao;
import firefly.dao.message.IPluginMessageDao;
import firefly.dao.message.IStageMessageDao;
import firefly.model.message.JobMessage;
import firefly.model.message.KafkaMessage;
import firefly.model.message.PipelineMessage;
import firefly.model.message.PluginMessage;
import firefly.model.message.StageMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class KafkaMessageStore {

    @Autowired
    private IPipelineMessageDao pipelineMessageDao;

    @Autowired
    private IStageMessageDao stageMessageDao;

    @Autowired
    private IJobMessageDao jobMessageDao;

    @Autowired
    private IPluginMessageDao pluginMessageDao;

    @Transactional
    public void savePipelineMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages(messages, pipelineMessageDao, this::toPipelineMessage);
    }

    @Transactional
    public void saveStageMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages(messages, stageMessageDao, this::toStageMessage);
    }

    @Transactional
    public void saveJobMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages(messages, jobMessageDao, this::toJobMessage);
    }

    @Transactional
    public void savePluginMessages(List<ConsumerRecord<String, String>> messages) {
        saveMessages(messages, pluginMessageDao, this::toPluginMessage);
    }

    private <T extends KafkaMessage> void saveMessages(
            List<ConsumerRecord<String, String>> messages,
            IKafkaMessageDao<T> messageDao,
            Function<StoredKafkaMessage, T> entityFactory
    ) {
        if (messages.isEmpty()) {
            return;
        }

        Map<String, StoredKafkaMessage> uniqueMessages = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> message : messages) {
            String messageUUID = extractMessageUUID(message);
            uniqueMessages.putIfAbsent(
                    messageUUID,
                    new StoredKafkaMessage(
                            messageUUID,
                            message.topic(),
                            message.partition(),
                            message.offset(),
                            message.key(),
                            message.value()
                    )
            );
        }

        Set<String> existingMessageUUIDs =
                messageDao.findExistingMessageUUIDs(List.copyOf(uniqueMessages.keySet()));

        List<T> newMessages = uniqueMessages.entrySet()
                .stream()
                .filter(entry -> !existingMessageUUIDs.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(entityFactory)
                .toList();
        if (!newMessages.isEmpty()) {
            messageDao.saveAll(newMessages);
        }
    }

    private PipelineMessage toPipelineMessage(StoredKafkaMessage message) {
        return new PipelineMessage(
                message.messageUUID(),
                message.topic(),
                message.kafkaPartition(),
                message.kafkaOffset(),
                message.messageKey(),
                message.payload()
        );
    }

    private StageMessage toStageMessage(StoredKafkaMessage message) {
        return new StageMessage(
                message.messageUUID(),
                message.topic(),
                message.kafkaPartition(),
                message.kafkaOffset(),
                message.messageKey(),
                message.payload()
        );
    }

    private JobMessage toJobMessage(StoredKafkaMessage message) {
        return new JobMessage(
                message.messageUUID(),
                message.topic(),
                message.kafkaPartition(),
                message.kafkaOffset(),
                message.messageKey(),
                message.payload()
        );
    }

    private PluginMessage toPluginMessage(StoredKafkaMessage message) {
        return new PluginMessage(
                message.messageUUID(),
                message.topic(),
                message.kafkaPartition(),
                message.kafkaOffset(),
                message.messageKey(),
                message.payload()
        );
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

    private record StoredKafkaMessage(
            String messageUUID,
            String topic,
            Integer kafkaPartition,
            Long kafkaOffset,
            String messageKey,
            String payload
    ) {
    }
}
