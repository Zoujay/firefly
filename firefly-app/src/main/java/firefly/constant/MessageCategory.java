package firefly.constant;

public enum MessageCategory {
    PIPELINE("pipeline"),
    STAGE("stage"),
    JOB("job"),
    PLUGIN("plugin");

    private final String value;

    MessageCategory(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
