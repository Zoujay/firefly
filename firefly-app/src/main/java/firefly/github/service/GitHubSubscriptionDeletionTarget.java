package firefly.github.service;

import firefly.github.model.GitHubRegistrationMode;

public record GitHubSubscriptionDeletionTarget(
    Long subscriptionId,
    String subscriptionPublicId,
    Long connectionId,
    String owner,
    String repositoryName,
    Long webhookId,
    GitHubRegistrationMode registrationMode) {

}
