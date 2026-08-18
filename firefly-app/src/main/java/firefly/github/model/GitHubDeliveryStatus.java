package firefly.github.model;

public enum GitHubDeliveryStatus {
    RECEIVED,
    PROCESSING,
    RETRYABLE,
    SUCCESS,
    IGNORED,
    REJECTED,
    DEAD
}
