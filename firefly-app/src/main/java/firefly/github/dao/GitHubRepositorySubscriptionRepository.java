package firefly.github.dao;

import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GitHubRepositorySubscriptionRepository
        extends JpaRepository<GitHubRepositorySubscriptionEntity, Long> {

    Optional<GitHubRepositorySubscriptionEntity> findByPublicId(String publicId);

    Optional<GitHubRepositorySubscriptionEntity> findByGithubRepositoryId(Long repositoryId);

    Optional<GitHubRepositorySubscriptionEntity> findByWebhookId(Long webhookId);

    List<GitHubRepositorySubscriptionEntity> findAllByConnectionId(Long connectionId);

    List<GitHubRepositorySubscriptionEntity> findAllByGithubRepositoryIdAndWebhookIdIsNullAndStatus(
            Long repositoryId, GitHubSubscriptionStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update GitHubRepositorySubscriptionEntity s
               set s.webhookId = :webhookId,
                   s.status = :active,
                   s.lastError = '',
                   s.updatedAt = :updatedAt
             where s.id = :id
               and s.webhookId is null
               and s.status = :provisioning
            """)
    int bindWebhookIfUnbound(
            @Param("id") Long id,
            @Param("webhookId") Long webhookId,
            @Param("provisioning") GitHubSubscriptionStatus provisioning,
            @Param("active") GitHubSubscriptionStatus active,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update GitHubRepositorySubscriptionEntity s
               set s.status = :active,
                   s.lastError = '',
                   s.updatedAt = :updatedAt
             where s.id = :id
               and s.webhookId = :webhookId
               and s.status = :provisioning
            """)
    int activateBoundWebhook(
            @Param("id") Long id,
            @Param("webhookId") Long webhookId,
            @Param("provisioning") GitHubSubscriptionStatus provisioning,
            @Param("active") GitHubSubscriptionStatus active,
            @Param("updatedAt") LocalDateTime updatedAt);
}
