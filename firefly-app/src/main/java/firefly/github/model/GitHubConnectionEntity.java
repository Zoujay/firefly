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
@Table(name = "github_connection")
public class GitHubConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id")
    private String publicId;

    @Column(name = "singleton_key")
    private String singletonKey;

    @Column(name = "github_user_id")
    private Long githubUserId;

    @Column(name = "github_login")
    private String githubLogin;

    @Column(name = "access_token_ciphertext")
    private String accessTokenCiphertext;

    @Column(name = "token_nonce")
    private byte[] tokenNonce;

    @Column(name = "encryption_key_version")
    private String encryptionKeyVersion;

    @Column(name = "scopes")
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private GitHubConnectionStatus status;

    @Column(name = "last_validated_at")
    private LocalDateTime lastValidatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
