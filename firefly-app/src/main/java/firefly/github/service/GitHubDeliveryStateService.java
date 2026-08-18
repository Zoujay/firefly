package firefly.github.service;

import firefly.github.config.GitHubProcessingProperties;
import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class GitHubDeliveryStateService {

  private final GitHubWebhookDeliveryRepository deliveryRepository;
  private final GitHubProcessingProperties processingProperties;
  private final Clock clock;

  public GitHubDeliveryStateService(
      GitHubWebhookDeliveryRepository deliveryRepository,
      GitHubProcessingProperties processingProperties,
      Clock clock) {
    this.deliveryRepository = deliveryRepository;
    this.processingProperties = processingProperties;
    this.clock = clock;
  }

  @Transactional
  public boolean claim(String deliveryId, String processorId) {
    return deliveryRepository.claim(
            deliveryId,
            processorId,
            now(),
            GitHubDeliveryStatus.PROCESSING,
            GitHubDeliveryStatus.RECEIVED,
            GitHubDeliveryStatus.RETRYABLE,
            processingProperties.getMaxAttempts())
        == 1;
  }

  @Transactional
  public boolean finish(
      String deliveryId, String processorId, GitHubDeliveryStatus status, String error) {
    GitHubWebhookDeliveryEntity delivery =
        deliveryRepository
            .findByDeliveryId(deliveryId)
            .orElseThrow(
                () -> new IllegalStateException("GitHub delivery was not found: " + deliveryId));
    GitHubDeliveryStatus finalStatus =
        status == GitHubDeliveryStatus.RETRYABLE
                && delivery.getProcessingAttempt() >= processingProperties.getMaxAttempts()
            ? GitHubDeliveryStatus.DEAD
            : status;
    LocalDateTime finishedAt = now();
    LocalDateTime nextRetryAt =
        finalStatus == GitHubDeliveryStatus.RETRYABLE
            ? finishedAt.plus(processingProperties.getRetryDelay())
            : null;
    int updated =
        deliveryRepository.finishOwned(
            deliveryId,
            processorId,
            GitHubDeliveryStatus.PROCESSING,
            finalStatus,
            error == null ? "" : truncate(error),
            finishedAt,
            nextRetryAt);
    if (updated == 0) {
      log.warn(
          "Ignored stale GitHub delivery completion for delivery {} by processor {}",
          deliveryId,
          processorId);
    }
    return updated == 1;
  }

  @Transactional
  public void requestRetry(String deliveryId) {
    GitHubWebhookDeliveryEntity delivery =
        deliveryRepository
            .findByDeliveryId(deliveryId)
            .orElseThrow(
                () -> new IllegalStateException("GitHub delivery was not found: " + deliveryId));
    if (delivery.getStatus() != GitHubDeliveryStatus.RETRYABLE
        && delivery.getStatus() != GitHubDeliveryStatus.DEAD) {
      throw new IllegalStateException("GitHub delivery is not retryable: " + delivery.getStatus());
    }
    delivery
        .setStatus(GitHubDeliveryStatus.RETRYABLE)
        .setProcessingAttempt(0)
        .setProcessorId("")
        .setProcessingStartedAt(null)
        .setNextRetryAt(now())
        .setLastError("");
    deliveryRepository.save(delivery);
  }

  @Transactional
  public void recoverExpired() {
    LocalDateTime current = now();
    LocalDateTime expiredBefore = current.minus(processingProperties.getLeaseTimeout());
    String error = "GitHub delivery processing lease expired";
    deliveryRepository.expireDead(
        expiredBefore,
        current,
        error,
        GitHubDeliveryStatus.PROCESSING,
        GitHubDeliveryStatus.DEAD,
        processingProperties.getMaxAttempts());
    deliveryRepository.recoverExpired(
        expiredBefore,
        current.plus(processingProperties.getRetryDelay()),
        error,
        GitHubDeliveryStatus.PROCESSING,
        GitHubDeliveryStatus.RETRYABLE,
        processingProperties.getMaxAttempts());
  }

  private String truncate(String value) {
    return value.length() <= 2048 ? value : value.substring(0, 2048);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
