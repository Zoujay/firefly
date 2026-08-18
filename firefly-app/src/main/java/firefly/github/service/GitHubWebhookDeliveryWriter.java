package firefly.github.service;

import firefly.constant.KafkaConfiguration;
import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.message.GitHubWebhookMessage;
import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.service.outbox.OutboxService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class GitHubWebhookDeliveryWriter {

  private final GitHubWebhookDeliveryRepository deliveryRepository;
  private final GitHubRepositorySubscriptionRepository subscriptionRepository;
  private final OutboxService outboxService;
  private final Clock clock;

  public GitHubWebhookDeliveryWriter(
      GitHubWebhookDeliveryRepository deliveryRepository,
      GitHubRepositorySubscriptionRepository subscriptionRepository,
      OutboxService outboxService,
      Clock clock) {
    this.deliveryRepository = deliveryRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.outboxService = outboxService;
    this.clock = clock;
  }

  @Transactional
  public GitHubDeliveryWriteResult persist(
      GitHubRepositorySubscriptionEntity subscription,
      GitHubWebhookEvent event,
      String rawPayload,
      Long headerHookId) {
    if (deliveryRepository.findByDeliveryId(event.deliveryId()).isPresent()) {
      return GitHubDeliveryWriteResult.duplicate();
    }
    LocalDateTime now = now();
    GitHubWebhookDeliveryEntity delivery =
        deliveryRepository.saveAndFlush(
            new GitHubWebhookDeliveryEntity()
                .setDeliveryId(event.deliveryId())
                .setSubscriptionId(subscription.getId())
                .setEventType(event.eventType())
                .setAction(event.action())
                .setRepositoryId(event.repositoryId())
                .setPayload(rawPayload)
                .setStatus(GitHubDeliveryStatus.RECEIVED)
                .setProcessingAttempt(0)
                .setProcessorId("")
                .setLastError("")
                .setReceivedAt(now));

    if (event.ping()) {
      String rejection = bindPing(subscription, event, headerHookId, now);
      if (rejection != null) {
        delivery
            .setStatus(GitHubDeliveryStatus.REJECTED)
            .setLastError(rejection)
            .setProcessingFinishedAt(now);
        deliveryRepository.save(delivery);
        log.warn(
            "Rejected GitHub ping delivery {} for subscription {}: {}",
            event.deliveryId(),
            subscription.getPublicId(),
            rejection);
        return GitHubDeliveryWriteResult.rejectedResult();
      }
      delivery.setStatus(GitHubDeliveryStatus.SUCCESS).setProcessingFinishedAt(now);
      deliveryRepository.save(delivery);
      return GitHubDeliveryWriteResult.accepted();
    }
    outboxService.enqueue(
        KafkaConfiguration.GITHUB_WEBHOOK_TOPIC,
        new GitHubWebhookMessage(event.deliveryId(), event.deliveryId()));
    return GitHubDeliveryWriteResult.accepted();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean persistRejectedBindingConflict(
      GitHubRepositorySubscriptionEntity subscription,
      GitHubWebhookEvent event,
      String rawPayload) {
    if (deliveryRepository.findByDeliveryId(event.deliveryId()).isPresent()) {
      return false;
    }
    LocalDateTime now = now();
    deliveryRepository.saveAndFlush(
        new GitHubWebhookDeliveryEntity()
            .setDeliveryId(event.deliveryId())
            .setSubscriptionId(subscription.getId())
            .setEventType(event.eventType())
            .setAction(event.action())
            .setRepositoryId(event.repositoryId())
            .setPayload(rawPayload)
            .setStatus(GitHubDeliveryStatus.REJECTED)
            .setProcessingAttempt(0)
            .setProcessorId("")
            .setLastError("GitHub Hook ID is already bound")
            .setReceivedAt(now)
            .setProcessingFinishedAt(now));
    log.warn(
        "Rejected GitHub ping delivery {} because Hook ID binding conflicted", event.deliveryId());
    return true;
  }

  private String bindPing(
      GitHubRepositorySubscriptionEntity subscription,
      GitHubWebhookEvent event,
      Long headerHookId,
      LocalDateTime now) {
    if (event.hookId() == null || !event.hookId().equals(headerHookId)) {
      return "GitHub ping Hook ID does not match its header";
    }
    if (subscription.getWebhookId() == null) {
      int bound =
          subscriptionRepository.bindWebhookIfUnbound(
              subscription.getId(),
              headerHookId,
              GitHubSubscriptionStatus.PROVISIONING,
              GitHubSubscriptionStatus.ACTIVE,
              now);
      if (bound == 1) {
        return null;
      }
      GitHubRepositorySubscriptionEntity current =
          subscriptionRepository.findById(subscription.getId()).orElse(null);
      return current != null
              && headerHookId.equals(current.getWebhookId())
              && current.getStatus() == GitHubSubscriptionStatus.ACTIVE
          ? null
          : "GitHub Hook ID was concurrently bound to a different webhook";
    } else if (!subscription.getWebhookId().equals(headerHookId)) {
      return "GitHub ping Hook ID does not match the subscription";
    }
    if (subscription.getStatus() == GitHubSubscriptionStatus.ACTIVE) {
      return null;
    }
    if (subscription.getStatus() != GitHubSubscriptionStatus.PROVISIONING) {
      return "GitHub subscription is not eligible for ping binding";
    }
    int activated =
        subscriptionRepository.activateBoundWebhook(
            subscription.getId(),
            headerHookId,
            GitHubSubscriptionStatus.PROVISIONING,
            GitHubSubscriptionStatus.ACTIVE,
            now);
    if (activated == 1) {
      return null;
    }
    GitHubRepositorySubscriptionEntity current =
        subscriptionRepository.findById(subscription.getId()).orElse(null);
    return current != null
            && headerHookId.equals(current.getWebhookId())
            && current.getStatus() == GitHubSubscriptionStatus.ACTIVE
        ? null
        : "GitHub subscription changed while ping binding was in progress";
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
