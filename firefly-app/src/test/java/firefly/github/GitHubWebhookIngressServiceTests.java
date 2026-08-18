package firefly.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.service.GitHubSubscriptionService;
import firefly.github.service.GitHubWebhookDeliveryWriter;
import firefly.github.service.GitHubWebhookIngressService;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.github.webhook.GitHubWebhookEventParser;
import firefly.github.webhook.GitHubWebhookSignatureVerifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookIngressServiceTests {

    @Mock
    private GitHubRepositorySubscriptionRepository subscriptionRepository;
    @Mock
    private GitHubSubscriptionService subscriptionService;
    @Mock
    private GitHubWebhookEventParser eventParser;
    @Mock
    private GitHubWebhookDeliveryWriter deliveryWriter;

    @Test
    void missingHookAndInvalidSignatureUseSameExternalError() {
        when(subscriptionRepository.findByWebhookId(99L)).thenReturn(Optional.empty());
        GitHubIntegrationException missing =
            assertThrows(
                GitHubIntegrationException.class,
                () ->
                    ingress()
                        .receive(
                            "delivery",
                            "push",
                            99L,
                            "repository",
                            2L,
                            "sha256=00",
                            "{}".getBytes()));
        GitHubRepositorySubscriptionEntity subscription =
            new GitHubRepositorySubscriptionEntity()
                .setId(1L)
                .setGithubRepositoryId(2L)
                .setStatus(GitHubSubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findByWebhookId(99L)).thenReturn(Optional.of(subscription));
        when(subscriptionService.webhookSecret(subscription)).thenReturn("secret");
        GitHubIntegrationException invalidSignature =
            assertThrows(
                GitHubIntegrationException.class,
                () ->
                    ingress()
                        .receive(
                            "delivery",
                            "push",
                            99L,
                            "repository",
                            2L,
                            "sha256=00",
                            "{}".getBytes()));

        assertEquals(missing.getCode(), invalidSignature.getCode());
        assertEquals(missing.getMessage(), invalidSignature.getMessage());
    }

    @Test
    void orphanedSubscriptionCannotEnterDeliveryPipeline() {
        GitHubRepositorySubscriptionEntity subscription =
            new GitHubRepositorySubscriptionEntity()
                .setId(1L)
                .setGithubRepositoryId(2L)
                .setStatus(GitHubSubscriptionStatus.ORPHANED);
        when(subscriptionRepository.findByWebhookId(99L)).thenReturn(Optional.of(subscription));
        when(subscriptionService.webhookSecret(subscription)).thenReturn("secret");
        GitHubWebhookEvent event =
            new GitHubWebhookEvent(
                "delivery",
                "push",
                null,
                2L,
                "acme/repo",
                "https://github.com/acme/repo",
                "https://github.com/acme/repo.git",
                null,
                "refs/heads/main",
                "main",
                null,
                "main",
                "abc",
                3L,
                "octocat",
                Instant.parse("2026-08-16T00:00:00Z"),
                false,
                null);
        when(eventParser.parse(any(), any(), any())).thenReturn(event);

        assertThrows(
            GitHubIntegrationException.class,
            () ->
                ingressWithNoopVerifier()
                    .receive(
                        "delivery",
                        "push",
                        99L,
                        "repository",
                        2L,
                        "ignored",
                        "{}".getBytes()));

        verify(deliveryWriter, never()).persist(any(), any(), any(), any());
    }

    private GitHubWebhookIngressService ingress() {
        return new GitHubWebhookIngressService(
            subscriptionRepository,
            subscriptionService,
            new GitHubWebhookSignatureVerifier(),
            eventParser,
            deliveryWriter);
    }

    private GitHubWebhookIngressService ingressWithNoopVerifier() {
        GitHubWebhookSignatureVerifier verifier =
            org.mockito.Mockito.mock(GitHubWebhookSignatureVerifier.class);
        return new GitHubWebhookIngressService(
            subscriptionRepository, subscriptionService, verifier, eventParser, deliveryWriter);
    }
}
