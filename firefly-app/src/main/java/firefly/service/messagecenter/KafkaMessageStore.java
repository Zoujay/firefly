package firefly.service.messagecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.dao.message.IJobMessageDao;
import firefly.dao.message.IPipelineMessageDao;
import firefly.dao.message.IPluginMessageDao;
import firefly.dao.message.IStageMessageDao;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KafkaMessageStore {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IPipelineMessageDao pipelineMessageDao;

    @Autowired
    private IStageMessageDao stageMessageDao;

    @Autowired
    private IJobMessageDao jobMessageDao;

    @Autowired
    private IPluginMessageDao pluginMessageDao;

    @Transactional
    public KafkaMessageSaveResult savePipelineMessages(List<ConsumerRecord<String, String>> messages) {
        return saveMessages(messages, pipelineMessageDao::insertIfAbsent);
    }

    @Transactional
    public KafkaMessageSaveResult saveStageMessages(List<ConsumerRecord<String, String>> messages) {
        return saveMessages(messages, stageMessageDao::insertIfAbsent);
    }

    @Transactional
    public KafkaMessageSaveResult saveJobMessages(List<ConsumerRecord<String, String>> messages) {
        return saveMessages(messages, jobMessageDao::insertIfAbsent);
    }

    @Transactional
    public KafkaMessageSaveResult savePluginMessages(List<ConsumerRecord<String, String>> messages) {
        return saveMessages(messages, pluginMessageDao::insertIfAbsent);
    }

    private KafkaMessageSaveResult saveMessages(
            List<ConsumerRecord<String, String>> messages,
            MessageInserter inserter
    ) {
        if (messages.isEmpty()) {
            return new KafkaMessageSaveResult(List.of(), 0);
        }

        Map<String, ConsumerRecord<String, String>> uniqueMessages = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> message : messages) {
            String messageUUID = extractMessageUUID(message);
            uniqueMessages.putIfAbsent(messageUUID, message);
        }

        List<ConsumerRecord<String, String>> newMessages = new ArrayList<>();
        for (Map.Entry<String, ConsumerRecord<String, String>> entry : uniqueMessages.entrySet()) {
            ConsumerRecord<String, String> message = entry.getValue();
            int inserted = inserter.insertIfAbsent(
                    entry.getKey(),
                    message.topic(),
                    message.partition(),
                    message.offset(),
                    message.key() == null ? StringUtils.EMPTY : message.key(),
                    message.value()
            );
            if (inserted == 1) {
                newMessages.add(message);
            }
        }

        return new KafkaMessageSaveResult(
                newMessages,
                messages.size() - newMessages.size()
        );
    }

    String extractMessageUUID(ConsumerRecord<String, String> message) {
        String payload = message.value();
        if (payload == null) {
            throw invalidMessage(message, "payload is null", null);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw invalidMessage(
                    message,
                    "payload is not valid JSON",
                    exception
            );
        }
        if (root == null || !root.isObject()) {
            throw invalidMessage(
                    message,
                    "payload is not a JSON object",
                    null
            );
        }

        JsonNode messageUUID = root.get("messageUUID");
        if (messageUUID == null
                || messageUUID.isNull()
                || !messageUUID.isTextual()) {
            throw invalidMessage(message, "messageUUID is missing", null);
        }

        String uuid = messageUUID.textValue();
        try {
            UUID.fromString(uuid);
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw invalidMessage(
                    message,
                    "messageUUID is not a valid UUID",
                    exception
            );
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

    @FunctionalInterface
    private interface MessageInserter {

        int insertIfAbsent(
                String messageUUID,
                String topic,
                Integer kafkaPartition,
                Long kafkaOffset,
                String messageKey,
                String payload
        );
    }
}
