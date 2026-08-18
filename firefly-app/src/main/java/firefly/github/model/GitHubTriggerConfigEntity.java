package firefly.github.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "github_trigger_config")
public class GitHubTriggerConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id")
    private Long pipelineId;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "events")
    private String events;

    @Column(name = "pull_request_actions")
    private String pullRequestActions;

    @Column(name = "ignore_delete_push")
    private Boolean ignoreDeletePush;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
