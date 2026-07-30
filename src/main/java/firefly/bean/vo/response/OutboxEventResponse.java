package firefly.bean.vo.response;

import firefly.constant.OutboxStatus;
import firefly.model.outbox.OutboxEvent;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class OutboxEventResponse {

    private String id;

    private String topic;

    private String messageKey;

    private String messageType;

    private String payload;

    private OutboxStatus publishStatus;

    private Integer publishAttempt;

    private String publisherID;

    private LocalDateTime publishingStartedAt;

    private LocalDateTime publishingFinishedAt;

    private String lastError;

    private LocalDateTime createdAt;

    public static OutboxEventResponse from(OutboxEvent event) {
        return new OutboxEventResponse(
                event.getId(),
                event.getTopic(),
                event.getMessageKey(),
                event.getMessageType(),
                event.getPayload(),
                event.getPublishStatus(),
                event.getPublishAttempt(),
                event.getPublisherID(),
                event.getPublishingStartedAt(),
                event.getPublishingFinishedAt(),
                event.getLastError(),
                event.getCreatedAt()
        );
    }
}
