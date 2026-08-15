package firefly.github.dao;

import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitHubRepositorySubscriptionRepository
        extends JpaRepository<GitHubRepositorySubscriptionEntity, Long> {

    Optional<GitHubRepositorySubscriptionEntity> findByPublicId(String publicId);

    Optional<GitHubRepositorySubscriptionEntity> findByGithubRepositoryId(Long repositoryId);

    Optional<GitHubRepositorySubscriptionEntity> findByWebhookId(Long webhookId);

    List<GitHubRepositorySubscriptionEntity> findAllByConnectionId(Long connectionId);

    List<GitHubRepositorySubscriptionEntity>
    findAllByGithubRepositoryIdAndWebhookIdIsNullAndStatus(
            Long repositoryId,
            GitHubSubscriptionStatus status
    );
}
