package firefly.service.messagecenter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import firefly.constant.BuildStatus;
import firefly.constant.PluginType;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class BusinessMessageUUIDTests {

    @Test
    void generatesStableUUIDForTheSameBusinessEvent() {
        String first = BusinessMessageUUID.stage(101L, 0, BuildStatus.RUNNING);
        String second = BusinessMessageUUID.stage(101L, 0, BuildStatus.RUNNING);

        assertEquals(first, second);
        assertDoesNotThrow(() -> UUID.fromString(first));
    }

    @Test
    void separatesDifferentBuildsStatusesAndMessageTypes() {
        String runningStage = BusinessMessageUUID.stage(101L, 0, BuildStatus.RUNNING);

        assertNotEquals(runningStage, BusinessMessageUUID.stage(102L, 0, BuildStatus.RUNNING));
        assertNotEquals(runningStage, BusinessMessageUUID.stage(101L, 0, BuildStatus.SUCCESS));
        assertNotEquals(runningStage, BusinessMessageUUID.stage(101L, 1, BuildStatus.RUNNING));
        assertNotEquals(runningStage, BusinessMessageUUID.job(101L, 0, BuildStatus.RUNNING));
        assertNotEquals(
            BusinessMessageUUID.plugin(PluginType.TEXT, 101L, 0, BuildStatus.RUNNING),
            BusinessMessageUUID.job(101L, 0, BuildStatus.RUNNING));
    }
}
