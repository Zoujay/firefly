package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BusinessMessageUUIDTests {

    @Test
    void generatesStableUUIDForTheSameBusinessEvent() {
        String first = BusinessMessageUUID.stage(101L, BuildStatus.RUNNING);
        String second = BusinessMessageUUID.stage(101L, BuildStatus.RUNNING);

        assertEquals(first, second);
        assertDoesNotThrow(() -> UUID.fromString(first));
    }

    @Test
    void separatesDifferentBuildsStatusesAndMessageTypes() {
        String runningStage = BusinessMessageUUID.stage(101L, BuildStatus.RUNNING);

        assertNotEquals(runningStage, BusinessMessageUUID.stage(102L, BuildStatus.RUNNING));
        assertNotEquals(runningStage, BusinessMessageUUID.stage(101L, BuildStatus.SUCCESS));
        assertNotEquals(runningStage, BusinessMessageUUID.job(101L, BuildStatus.RUNNING));
        assertNotEquals(
                BusinessMessageUUID.plugin(PluginType.TEXT, 101L, BuildStatus.RUNNING),
                BusinessMessageUUID.job(101L, BuildStatus.RUNNING)
        );
    }
}
