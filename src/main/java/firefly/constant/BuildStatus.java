package firefly.constant;

public enum BuildStatus {
    RUNNING("running"),
    PENDING("pending"),
    SUCCESS("success"),
    FAILURE("failure");

    private String value;
    BuildStatus(String value) {
        this.value = value;
    }
}
