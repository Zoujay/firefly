package firefly.github.service;

import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dto.GitHubWebhookResponse;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.github.webhook.GitHubWebhookEventParser;
import firefly.github.webhook.GitHubWebhookSignatureVerifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class GitHubWebhookIngressService {

    private final GitHubRepositorySubscriptionRepository subscriptionRepository;
    private final GitHubSubscriptionService subscriptionService;
    private final GitHubWebhookSignatureVerifier signatureVerifier;
    private final GitHubWebhookEventParser eventParser;
    private final GitHubWebhookDeliveryWriter deliveryWriter;

    public GitHubWebhookIngressService(
            GitHubRepositorySubscriptionRepository subscriptionRepository,
            GitHubSubscriptionService subscriptionService,
            GitHubWebhookSignatureVerifier signatureVerifier,
            GitHubWebhookEventParser eventParser,
            GitHubWebhookDeliveryWriter deliveryWriter
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.signatureVerifier = signatureVerifier;
        this.eventParser = eventParser;
        this.deliveryWriter = deliveryWriter;
    }

    public GitHubWebhookResponse receive(
            String deliveryId,
            String eventType,
            Long hookId,
            String targetType,
            Long targetId,
            String signature,
            byte[] rawPayload
    ) {
        if (!"repository".equalsIgnoreCase(targetType)) {
            throw forbidden("Only repository webhooks are supported");
        }
        GitHubRepositorySubscriptionEntity subscription = locate(
                eventType,
                hookId,
                targetId
        );
        signatureVerifier.verify(
                rawPayload,
                signature,
                subscriptionService.webhookSecret(subscription)
        );
        GitHubWebhookEvent event = eventParser.parse(deliveryId, eventType, rawPayload);
        if (event.repositoryId() == null
                || !event.repositoryId().equals(targetId)
                || !event.repositoryId().equals(subscription.getGithubRepositoryId())) {
            throw forbidden("GitHub repository identity does not match the subscription");
        }
        if (!event.ping() && subscription.getStatus() != GitHubSubscriptionStatus.ACTIVE) {
            throw forbidden("GitHub subscription is not active");
        }
        try {
            boolean created = deliveryWriter.persist(
                    subscription,
                    event,
                    new String(rawPayload, StandardCharsets.UTF_8),
                    hookId
            );
            return new GitHubWebhookResponse(
                    created ? "ACCEPTED" : "DUPLICATE",
                    deliveryId,
                    eventType
            );
        } catch (DataIntegrityViolationException exception) {
            return new GitHubWebhookResponse("DUPLICATE", deliveryId, eventType);
        }
    }

    private GitHubRepositorySubscriptionEntity locate(
            String eventType,
            Long hookId,
            Long targetId
    ) {
        GitHubRepositorySubscriptionEntity byHook = subscriptionRepository
                .findByWebhookId(hookId)
                .orElse(null);
        if (byHook != null) {
            return byHook;
        }
        if (!"ping".equals(eventType)) {
            throw forbidden("GitHub webhook subscription was not found");
        }
        List<GitHubRepositorySubscriptionEntity> candidates = subscriptionRepository
                .findAllByGithubRepositoryIdAndWebhookIdIsNullAndStatus(
                        targetId,
                        GitHubSubscriptionStatus.PROVISIONING
                );
        if (candidates.size() != 1) {
            throw forbidden("GitHub webhook subscription was not uniquely identified");
        }
        return candidates.getFirst();
    }

    private GitHubIntegrationException forbidden(String message) {
        return new GitHubIntegrationException(
                HttpStatus.FORBIDDEN,
                "GITHUB_WEBHOOK_FORBIDDEN",
                message
        );
    }
}
