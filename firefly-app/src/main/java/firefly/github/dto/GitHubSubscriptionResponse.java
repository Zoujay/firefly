package firefly.github.dto;

import firefly.github.model.GitHubRegistrationMode;
import firefly.github.model.GitHubSubscriptionStatus;

import java.net.URI;
import java.util.List;

public record GitHubSubscriptionResponse(
    String subscriptionId,
    Long repositoryId,
    String fullName,
    Long webhookId,
    GitHubRegistrationMode registrationMode,
    GitHubSubscriptionStatus status,
    URI payloadUrl,
    String secret,
    List<String> events) {

}
