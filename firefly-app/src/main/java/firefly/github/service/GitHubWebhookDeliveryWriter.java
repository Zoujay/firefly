package firefly.github.service;

import firefly.constant.KafkaConfiguration;
import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.message.GitHubWebhookMessage;
import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.service.outbox.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class GitHubWebhookDeliveryWriter {

    private final GitHubWebhookDeliveryRepository deliveryRepository;
    private final GitHubRepositorySubscriptionRepository subscriptionRepository;
    private final OutboxService outboxService;
    private final Clock clock;

    public GitHubWebhookDeliveryWriter(
            GitHubWebhookDeliveryRepository deliveryRepository,
            GitHubRepositorySubscriptionRepository subscriptionRepository,
            OutboxService outboxService,
            Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Transactional
    public boolean persist(
            GitHubRepositorySubscriptionEntity subscription,
            GitHubWebhookEvent event,
            String rawPayload,
            Long headerHookId
    ) {
        if (deliveryRepository.findByDeliveryId(event.deliveryId()).isPresent()) {
            return false;
        }
        LocalDateTime now = now();
        GitHubDeliveryStatus status = event.ping()
                ? GitHubDeliveryStatus.SUCCESS
                : GitHubDeliveryStatus.RECEIVED;
        deliveryRepository.saveAndFlush(new GitHubWebhookDeliveryEntity()
                .setDeliveryId(event.deliveryId())
                .setSubscriptionId(subscription.getId())
                .setEventType(event.eventType())
                .setAction(event.action())
                .setRepositoryId(event.repositoryId())
                .setPayload(rawPayload)
                .setStatus(status)
                .setProcessingAttempt(0)
                .setProcessorId("")
                .setLastError("")
                .setReceivedAt(now));

        if (event.ping()) {
            bindPing(subscription, event, headerHookId, now);
            return true;
        }
        outboxService.enqueue(
                KafkaConfiguration.GITHUB_WEBHOOK_TOPIC,
                new GitHubWebhookMessage(event.deliveryId(), event.deliveryId())
        );
        return true;
    }

    private void bindPing(
            GitHubRepositorySubscriptionEntity subscription,
            GitHubWebhookEvent event,
            Long headerHookId,
            LocalDateTime now
    ) {
        if (event.hookId() == null || !event.hookId().equals(headerHookId)) {
            throw forbidden("GitHub ping Hook ID does not match its header");
        }
        if (subscription.getWebhookId() == null) {
            subscriptionRepository.findByWebhookId(headerHookId)
                    .filter(other -> !other.getId().equals(subscription.getId()))
                    .ifPresent(other -> {
                        throw forbidden("GitHub Hook ID is already bound");
                    });
            subscription.setWebhookId(headerHookId);
        } else if (!subscription.getWebhookId().equals(headerHookId)) {
            throw forbidden("GitHub ping Hook ID does not match the subscription");
        }
        subscription.setStatus(GitHubSubscriptionStatus.ACTIVE)
                .setLastError("")
                .setUpdatedAt(now);
        subscriptionRepository.save(subscription);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private GitHubIntegrationException forbidden(String message) {
        return new GitHubIntegrationException(
                HttpStatus.FORBIDDEN,
                "GITHUB_WEBHOOK_ID_INVALID",
                message
        );
    }
}
