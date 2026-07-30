package firefly.model.outbox;

import firefly.constant.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

import static firefly.constant.PersistenceDefaults.UNSET_TIME;

/**
 * Durable record of a Kafka message that Firefly intends to publish.
 *
 * <p>Outbox solves the gap between changing MySQL state and sending Kafka:
 * the business changes and this row are committed in one database
 * transaction. Kafka publication happens only after that commit. A crash or
 * send failure therefore leaves an explicit PENDING/PUBLISHING/FAILED row for
 * manual recovery instead of silently losing the downstream event.</p>
 */
@Getter
@Entity
@Table(
        name = "outbox_event",
        indexes = @Index(
                name = "idx_outbox_status_created",
                columnList = "publish_status,created_at"
        )
)
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id = StringUtils.EMPTY;

    @Column(name = "topic", nullable = false, updatable = false, length = 249)
    private String topic = StringUtils.EMPTY;

    @Column(name = "message_key", nullable = false, updatable = false, length = 128)
    private String messageKey = StringUtils.EMPTY;

    @Column(name = "message_type", nullable = false, updatable = false, length = 128)
    private String messageType = StringUtils.EMPTY;

    @Lob
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload = StringUtils.EMPTY;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", nullable = false, length = 32)
    private OutboxStatus publishStatus = OutboxStatus.PENDING;

    @Column(name = "publish_attempt", nullable = false)
    private Integer publishAttempt = 0;

    @Column(name = "publisher_id", nullable = false, length = 128)
    private String publisherID = StringUtils.EMPTY;

    @Column(name = "publishing_started_at", nullable = false)
    private LocalDateTime publishingStartedAt = UNSET_TIME;

    @Column(name = "publishing_finished_at", nullable = false)
    private LocalDateTime publishingFinishedAt = UNSET_TIME;

    @Column(name = "last_error", nullable = false, length = 2048)
    private String lastError = StringUtils.EMPTY;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
