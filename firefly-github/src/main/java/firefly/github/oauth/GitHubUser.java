package firefly.github.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubUser(
        Long id,
        String login,
        String name,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("html_url") String htmlUrl
) {
}
