package firefly.github.oauth;

import firefly.github.config.GitHubProperties;
import firefly.github.http.GitHubIntegrationException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.StringJoiner;

public class GitHubOAuthClient {

    private final GitHubProperties properties;
    private final RestClient restClient;

    public GitHubOAuthClient(GitHubProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.clone().build();
    }

    public URI createAuthorizationUri(String state, String codeChallenge) {
        requireConfigured();
        if (!StringUtils.hasText(state) || !StringUtils.hasText(codeChallenge)) {
            throw new IllegalArgumentException("OAuth state and PKCE challenge are required");
        }
        StringJoiner scope = new StringJoiner(" ");
        properties.getScopes().forEach(scope::add);
        return UriComponentsBuilder.fromUri(properties.getOauthBaseUrl())
                .path("/login/oauth/authorize")
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("scope", scope.toString())
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUri();
    }

    public GitHubOAuthResult exchange(String code, String codeVerifier) {
        requireConfigured();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(codeVerifier)) {
            throw new GitHubIntegrationException(
                    HttpStatus.BAD_REQUEST,
                    "GITHUB_OAUTH_CALLBACK_INVALID",
                    "OAuth code and PKCE verifier are required");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri().toString());
        form.add("code_verifier", codeVerifier);

        try {
            GitHubOAuthToken token =
                    restClient
                            .post()
                            .uri(properties.getOauthBaseUrl().resolve("/login/oauth/access_token"))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(form)
                            .retrieve()
                            .body(GitHubOAuthToken.class);
            if (token == null || !StringUtils.hasText(token.accessToken())) {
                throw upstream("GitHub returned an empty OAuth access token", null);
            }
            GitHubUser user =
                    restClient
                            .get()
                            .uri(properties.getApiBaseUrl().resolve("/user"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                            .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", properties.getApiVersion())
                            .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
                            .retrieve()
                            .body(GitHubUser.class);
            if (user == null || user.id() == null || !StringUtils.hasText(user.login())) {
                throw upstream("GitHub returned an invalid user", null);
            }
            return new GitHubOAuthResult(token, user);
        } catch (GitHubIntegrationException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw upstream("GitHub OAuth exchange failed", exception);
        }
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(properties.getClientId())
                || !StringUtils.hasText(properties.getClientSecret())
                || properties.getRedirectUri() == null) {
            throw new GitHubIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GITHUB_OAUTH_NOT_CONFIGURED",
                    "GitHub OAuth client is not configured");
        }
    }

    private GitHubIntegrationException upstream(String message, Throwable cause) {
        return new GitHubIntegrationException(
                HttpStatus.BAD_GATEWAY, "GITHUB_UPSTREAM_ERROR", message, cause);
    }
}
