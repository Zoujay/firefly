package firefly.github.service;

import firefly.github.api.GitHubApiClient;
import firefly.github.dao.GitHubConnectionRepository;
import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubTriggerConfigRepository;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubConnectionEntity;
import firefly.github.model.GitHubConnectionStatus;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.security.GitHubSecretCipher;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class GitHubConnectionDisconnectService {

    private final GitHubConnectionRepository connectionRepository;
    private final GitHubRepositorySubscriptionRepository subscriptionRepository;
    private final GitHubTriggerConfigRepository triggerConfigRepository;
    private final GitHubApiClient apiClient;
    private final GitHubSecretCipher secretCipher;
    private final Clock clock;

    public GitHubConnectionDisconnectService(
            GitHubConnectionRepository connectionRepository,
            GitHubRepositorySubscriptionRepository subscriptionRepository,
            GitHubTriggerConfigRepository triggerConfigRepository,
            GitHubApiClient apiClient,
            GitHubSecretCipher secretCipher,
            Clock clock) {
        this.connectionRepository = connectionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.triggerConfigRepository = triggerConfigRepository;
        this.apiClient = apiClient;
        this.secretCipher = secretCipher;
        this.clock = clock;
    }

    public void disconnect(String publicId) {
        GitHubConnectionEntity connection =
                connectionRepository
                        .findByPublicId(publicId)
                        .orElseThrow(
                                () ->
                                        new GitHubIntegrationException(
                                                HttpStatus.NOT_FOUND,
                                                "GITHUB_CONNECTION_NOT_FOUND",
                                                "GitHub connection was not found"));
        connection.setStatus(GitHubConnectionStatus.DISCONNECTING).setUpdatedAt(now());
        connectionRepository.saveAndFlush(connection);

        String token =
                secretCipher.decrypt(
                        connection.getAccessTokenCiphertext(),
                        connection.getTokenNonce(),
                        connection.getEncryptionKeyVersion());
        List<String> failures = new ArrayList<>();
        for (GitHubRepositorySubscriptionEntity subscription :
                subscriptionRepository.findAllByConnectionId(connection.getId())) {
            disable(subscription);
            try {
                if (subscription.getWebhookId() != null) {
                    apiClient.deleteWebhook(
                            token,
                            subscription.getOwner(),
                            subscription.getRepositoryName(),
                            subscription.getWebhookId());
                }
                subscription
                        .setConnectionId(null)
                        .setStatus(GitHubSubscriptionStatus.DELETED)
                        .setLastError("")
                        .setUpdatedAt(now());
            } catch (RuntimeException exception) {
                subscription
                        .setStatus(GitHubSubscriptionStatus.ORPHANED)
                        .setLastError("Remote GitHub webhook cleanup failed")
                        .setUpdatedAt(now());
                failures.add(subscription.getPublicId());
            }
            subscriptionRepository.save(subscription);
        }
        if (!failures.isEmpty()) {
            throw new GitHubIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_DISCONNECT_INCOMPLETE",
                    "Some GitHub webhooks could not be removed; the connection remains"
                            + " DISCONNECTING");
        }
        connectionRepository.delete(connection);
    }

    private void disable(GitHubRepositorySubscriptionEntity subscription) {
        subscription.setStatus(GitHubSubscriptionStatus.DELETING).setUpdatedAt(now());
        subscriptionRepository.saveAndFlush(subscription);
        var configs = triggerConfigRepository.findAllBySubscriptionId(subscription.getId());
        configs.forEach(
                config ->
                        config.setEnabled(false)
                                .setDisabledReason("CONNECTION_DISCONNECTED")
                                .setUpdatedAt(now()));
        triggerConfigRepository.saveAll(configs);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
