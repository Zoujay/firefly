package firefly.model.trigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Entity
@Table(name = "github_trigger")
public class GithubTriggerEntity extends BaseTriggerEntity {

    @Column(name = "github_repo_url")
    private String githubRepoURL;

    @Column(name = "delivery_id")
    private String deliveryId;

    @Column(name = "pipeline_id")
    private Long pipelineId;

    @Column(name = "pipeline_build_id")
    private Long pipelineBuildId;

    @Column(name = "github_repository_id")
    private Long githubRepositoryId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "action")
    private String action;

    @Column(name = "source_branch")
    private String sourceBranch;

    @Column(name = "target_branch")
    private String targetBranch;

    @Column(name = "head_sha")
    private String headSha;

    @Column(name = "legacy_record")
    private Boolean legacyRecord;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void initializeAuditFields() {
        if (legacyRecord == null) {
            legacyRecord = false;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
