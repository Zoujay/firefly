package firefly.github.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubOAuthToken(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    String scope) {}
