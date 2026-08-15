package firefly.github.service;

public record GitHubDeliveryWriteResult(boolean created, boolean rejected) {

    public static GitHubDeliveryWriteResult duplicate() {
        return new GitHubDeliveryWriteResult(false, false);
    }

    public static GitHubDeliveryWriteResult accepted() {
        return new GitHubDeliveryWriteResult(true, false);
    }

    public static GitHubDeliveryWriteResult rejectedResult() {
        return new GitHubDeliveryWriteResult(true, true);
    }
}
