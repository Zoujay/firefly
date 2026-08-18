package firefly.github.http;

import java.time.Instant;

public record GitHubErrorResponse(String code, String message, Instant timestamp) {}
