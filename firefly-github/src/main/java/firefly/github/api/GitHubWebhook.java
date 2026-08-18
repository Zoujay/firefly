package firefly.github.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GitHubWebhook(
    Long id,
    String name,
    boolean active,
    List<String> events,
    GitHubWebhookConfig config,
    String url,
    @JsonProperty("ping_url") String pingUrl,
    @JsonProperty("test_url") String testUrl) {

}
