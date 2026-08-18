package firefly.constant;

import java.time.LocalDateTime;

public final class PersistenceDefaults {

    public static final LocalDateTime UNSET_TIME = LocalDateTime.of(1970, 1, 1, 0, 0);

    private PersistenceDefaults() {
    }
}
