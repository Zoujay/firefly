package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.constant.PluginType;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class BusinessMessageUUID {

    private BusinessMessageUUID() {
    }

    public static String pipeline(
        Long pipelineBuildID, Integer executionAttempt, BuildStatus status) {
        return generate("pipeline", pipelineBuildID, executionAttempt, status);
    }

    public static String stage(Long stageBuildID, Integer executionAttempt, BuildStatus status) {
        return generate("stage", stageBuildID, executionAttempt, status);
    }

    public static String job(Long jobBuildID, Integer executionAttempt, BuildStatus status) {
        return generate("job", jobBuildID, executionAttempt, status);
    }

    public static String plugin(
        PluginType pluginType,
        Long pluginBuildID,
        Integer executionAttempt,
        BuildStatus status) {
        Objects.requireNonNull(pluginType, "pluginType must not be null");
        return generate("plugin:" + pluginType.name(), pluginBuildID, executionAttempt, status);
    }

    private static String generate(
        String messageType, Long buildID, Integer executionAttempt, BuildStatus status) {
        Objects.requireNonNull(buildID, "buildID must not be null");
        Objects.requireNonNull(executionAttempt, "executionAttempt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (executionAttempt < 0) {
            throw new IllegalArgumentException("executionAttempt must not be negative");
        }
        String businessKey =
            "firefly:"
                + messageType
                + ":"
                + buildID
                + ":"
                + executionAttempt
                + ":"
                + status.name();
        return UUID.nameUUIDFromBytes(businessKey.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
