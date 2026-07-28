package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.constant.PluginType;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class BusinessMessageUUID {

    private BusinessMessageUUID() {
    }

    public static String pipeline(Long pipelineBuildID, BuildStatus status) {
        return generate("pipeline", pipelineBuildID, status);
    }

    public static String stage(Long stageBuildID, BuildStatus status) {
        return generate("stage", stageBuildID, status);
    }

    public static String job(Long jobBuildID, BuildStatus status) {
        return generate("job", jobBuildID, status);
    }

    public static String plugin(PluginType pluginType, Long pluginBuildID, BuildStatus status) {
        Objects.requireNonNull(pluginType, "pluginType must not be null");
        return generate("plugin:" + pluginType.name(), pluginBuildID, status);
    }

    private static String generate(String messageType, Long buildID, BuildStatus status) {
        Objects.requireNonNull(buildID, "buildID must not be null");
        Objects.requireNonNull(status, "status must not be null");
        String businessKey = "firefly:" + messageType + ":" + buildID + ":" + status.name();
        return UUID.nameUUIDFromBytes(businessKey.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
