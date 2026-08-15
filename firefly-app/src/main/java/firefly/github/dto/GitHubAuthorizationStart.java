package firefly.github.dto;

import java.net.URI;
import java.time.Duration;

public record GitHubAuthorizationStart(
        URI authorizationUri,
        String browserSession,
        Duration ttl
) {
}
