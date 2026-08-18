package firefly.github.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "github_oauth_state")
public class GitHubOAuthStateEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "state")
  private String state;

  @Column(name = "session_hash")
  private String sessionHash;

  @Column(name = "code_verifier")
  private String codeVerifier;

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  @Column(name = "consumed_at")
  private LocalDateTime consumedAt;

  @Column(name = "created_at")
  private LocalDateTime createdAt;
}
