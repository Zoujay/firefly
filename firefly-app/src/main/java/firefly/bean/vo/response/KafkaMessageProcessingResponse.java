package firefly.bean.vo.response;

import firefly.constant.MessageCategory;
import firefly.constant.MessageProcessingStatus;
import firefly.model.message.KafkaMessage;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class KafkaMessageProcessingResponse {

    private MessageCategory category;

    private String messageUUID;

    private String topic;

    private Integer kafkaPartition;

    private Long kafkaOffset;

    private String messageKey;

    private String payload;

    private LocalDateTime receivedAt;

    private MessageProcessingStatus processingStatus;

    private Integer processingAttempt;

    private String processorID;

    private LocalDateTime processingStartedAt;

    private LocalDateTime processingFinishedAt;

    private String lastError;

    public static KafkaMessageProcessingResponse from(
        MessageCategory category, KafkaMessage message) {
        return new KafkaMessageProcessingResponse(
            category,
            message.getMessageUUID(),
            message.getTopic(),
            message.getKafkaPartition(),
            message.getKafkaOffset(),
            message.getMessageKey(),
            message.getPayload(),
            message.getReceivedAt(),
            message.getProcessingStatus(),
            message.getProcessingAttempt(),
            message.getProcessorID(),
            message.getProcessingStartedAt(),
            message.getProcessingFinishedAt(),
            message.getLastError());
    }
}
