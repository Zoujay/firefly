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
@Table(name = "github_repository_subscription")
public class GitHubRepositorySubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id")
    private String publicId;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "github_repository_id")
    private Long githubRepositoryId;

    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "owner")
    private String owner;

    @Column(name = "repository_name")
    private String repositoryName;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "html_url")
    private String htmlUrl;

    @Column(name = "clone_url")
    private String cloneUrl;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "webhook_id")
    private Long webhookId;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_mode")
    private GitHubRegistrationMode registrationMode;

    @Column(name = "webhook_secret_ciphertext")
    private String webhookSecretCiphertext;

    @Column(name = "webhook_secret_nonce")
    private byte[] webhookSecretNonce;

    @Column(name = "webhook_secret_key_version")
    private String webhookSecretKeyVersion;

    @Column(name = "events")
    private String events;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private GitHubSubscriptionStatus status;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
