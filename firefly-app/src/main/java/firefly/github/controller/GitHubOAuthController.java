package firefly.github.controller;

import firefly.github.dto.GitHubAuthorizationStart;
import firefly.github.dto.GitHubConnectionResponse;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.service.GitHubConnectionDisconnectService;
import firefly.github.service.GitHubConnectionService;
import firefly.github.service.GitHubOAuthStateService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/github")
public class GitHubOAuthController {

    static final String SESSION_COOKIE = "firefly_github_oauth_session";
    private final GitHubOAuthStateService stateService;
    private final GitHubConnectionService connectionService;
    private final GitHubConnectionDisconnectService disconnectService;

    public GitHubOAuthController(
        GitHubOAuthStateService stateService,
        GitHubConnectionService connectionService,
        GitHubConnectionDisconnectService disconnectService) {
        this.stateService = stateService;
        this.connectionService = connectionService;
        this.disconnectService = disconnectService;
    }

    @GetMapping("/oauth/authorize")
    public ResponseEntity<Void> authorize() {
        GitHubAuthorizationStart start = stateService.create();
        ResponseCookie cookie =
            ResponseCookie.from(SESSION_COOKIE, start.browserSession())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/github/oauth")
                .maxAge(start.ttl())
                .build();
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, start.authorizationUri().toString())
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .build();
    }

    @GetMapping("/oauth/callback")
    public GitHubConnectionResponse callback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        @CookieValue(name = SESSION_COOKIE, required = false) String browserSession,
        HttpServletResponse response) {
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/github/oauth")
                .maxAge(0)
                .build()
                .toString());
        if (StringUtils.hasText(error)) {
            throw new GitHubIntegrationException(
                HttpStatus.BAD_REQUEST,
                "GITHUB_OAUTH_DENIED",
                "GitHub OAuth authorization was denied");
        }
        return connectionService.complete(code, state, browserSession);
    }

    @GetMapping("/connections")
    public List<GitHubConnectionResponse> connections() {
        return connectionService.list();
    }

    @DeleteMapping("/connections/{connectionId}")
    public ResponseEntity<Void> disconnect(@PathVariable String connectionId) {
        disconnectService.disconnect(connectionId);
        return ResponseEntity.noContent().build();
    }
}
