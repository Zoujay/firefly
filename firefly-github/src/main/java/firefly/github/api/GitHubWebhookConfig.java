package firefly.github.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubWebhookConfig(
    String url,
    @JsonProperty("content_type") String contentType,
    @JsonProperty("insecure_ssl") String insecureSsl) {}
