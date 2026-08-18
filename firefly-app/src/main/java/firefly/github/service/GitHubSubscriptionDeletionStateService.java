package firefly.github.service;

import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubTriggerConfigRepository;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitHubSubscriptionDeletionStateService {

  private final GitHubRepositorySubscriptionRepository subscriptionRepository;
  private final GitHubTriggerConfigRepository triggerConfigRepository;
  private final Clock clock;

  public GitHubSubscriptionDeletionStateService(
      GitHubRepositorySubscriptionRepository subscriptionRepository,
      GitHubTriggerConfigRepository triggerConfigRepository,
      Clock clock) {
    this.subscriptionRepository = subscriptionRepository;
    this.triggerConfigRepository = triggerConfigRepository;
    this.clock = clock;
  }

  @Transactional
  public GitHubSubscriptionDeletionTarget begin(String subscriptionPublicId) {
    GitHubRepositorySubscriptionEntity subscription =
        subscriptionRepository
            .findByPublicId(subscriptionPublicId)
            .orElseThrow(() -> notFound(subscriptionPublicId));
    LocalDateTime now = now();
    subscription.setStatus(GitHubSubscriptionStatus.DELETING).setLastError("").setUpdatedAt(now);
    subscriptionRepository.save(subscription);

    var configs = triggerConfigRepository.findAllBySubscriptionId(subscription.getId());
    configs.forEach(
        config ->
            config.setEnabled(false).setDisabledReason("SUBSCRIPTION_DELETED").setUpdatedAt(now));
    triggerConfigRepository.saveAll(configs);

    return new GitHubSubscriptionDeletionTarget(
        subscription.getId(),
        subscription.getPublicId(),
        subscription.getConnectionId(),
        subscription.getOwner(),
        subscription.getRepositoryName(),
        subscription.getWebhookId(),
        subscription.getRegistrationMode());
  }

  @Transactional
  public void complete(Long subscriptionId) {
    update(subscriptionId, GitHubSubscriptionStatus.DELETED, "");
  }

  @Transactional
  public void fail(Long subscriptionId, GitHubSubscriptionStatus status, String error) {
    update(subscriptionId, status, truncate(error));
  }

  private void update(Long subscriptionId, GitHubSubscriptionStatus status, String error) {
    GitHubRepositorySubscriptionEntity subscription =
        subscriptionRepository
            .findById(subscriptionId)
            .orElseThrow(() -> notFound(String.valueOf(subscriptionId)));
    subscription.setStatus(status).setLastError(error).setUpdatedAt(now());
    subscriptionRepository.save(subscription);
  }

  private String truncate(String error) {
    if (error == null || error.isBlank()) {
      return "Remote GitHub webhook cleanup failed";
    }
    return error.length() <= 2048 ? error : error.substring(0, 2048);
  }

  private GitHubIntegrationException notFound(String id) {
    return new GitHubIntegrationException(
        HttpStatus.NOT_FOUND,
        "GITHUB_SUBSCRIPTION_NOT_FOUND",
        "GitHub repository subscription was not found: " + id);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
