package firefly.github.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "github_webhook_delivery")
public class GitHubWebhookDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id")
    private String deliveryId;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "action")
    private String action;

    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private GitHubDeliveryStatus status;

    @Column(name = "processing_attempt")
    private Integer processingAttempt;

    @Column(name = "processor_id")
    private String processorId;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "processing_finished_at")
    private LocalDateTime processingFinishedAt;
}
