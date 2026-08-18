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
@Table(name = "github_delivery_pipeline")
public class GitHubDeliveryPipelineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id")
    private String deliveryId;

    @Column(name = "pipeline_id")
    private Long pipelineId;

    @Column(name = "pipeline_build_id")
    private Long pipelineBuildId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private GitHubDeliveryPipelineStatus status;

    @Column(name = "processing_attempt")
    private Integer processingAttempt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
