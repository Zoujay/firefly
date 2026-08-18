package firefly.github.service;

import firefly.github.config.GitHubProperties;
import firefly.github.dao.GitHubOAuthStateRepository;
import firefly.github.dto.GitHubAuthorizationStart;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubOAuthStateEntity;
import firefly.github.oauth.GitHubOAuthClient;
import firefly.github.oauth.PkceGenerator;
import firefly.github.oauth.PkcePair;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

@Service
public class GitHubOAuthStateService {

    private static final int RANDOM_BYTES = 32;
    private final GitHubOAuthStateRepository stateRepository;
    private final GitHubOAuthStateWriter stateWriter;
    private final GitHubOAuthClient oauthClient;
    private final GitHubProperties properties;
    private final PkceGenerator pkceGenerator;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public GitHubOAuthStateService(
        GitHubOAuthStateRepository stateRepository,
        GitHubOAuthStateWriter stateWriter,
        GitHubOAuthClient oauthClient,
        GitHubProperties properties,
        PkceGenerator pkceGenerator,
        SecureRandom secureRandom,
        Clock clock) {
        this.stateRepository = stateRepository;
        this.stateWriter = stateWriter;
        this.oauthClient = oauthClient;
        this.properties = properties;
        this.pkceGenerator = pkceGenerator;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    @Transactional
    public GitHubAuthorizationStart create() {
        String state = randomValue();
        String browserSession = randomValue();
        PkcePair pkce = pkceGenerator.create();
        LocalDateTime now = now();
        stateRepository.save(
            new GitHubOAuthStateEntity()
                .setState(state)
                .setSessionHash(hash(browserSession))
                .setCodeVerifier(pkce.verifier())
                .setCreatedAt(now)
                .setExpiresAt(now.plus(properties.getStateTtl())));
        return new GitHubAuthorizationStart(
            oauthClient.createAuthorizationUri(state, pkce.challenge()),
            browserSession,
            properties.getStateTtl());
    }

    public String consume(String state, String browserSession) {
        GitHubOAuthStateEntity pending = stateWriter.take(state).orElseThrow(this::invalidState);
        if (pending.getConsumedAt() != null
            || pending.getExpiresAt().isBefore(now())
            || !MessageDigest.isEqual(
            pending.getSessionHash().getBytes(StandardCharsets.US_ASCII),
            hash(browserSession).getBytes(StandardCharsets.US_ASCII))) {
            throw invalidState();
        }
        return pending.getCodeVerifier();
    }

    private String randomValue() {
        byte[] value = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String hash(String value) {
        if (value == null) {
            return "";
        }
        try {
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private GitHubIntegrationException invalidState() {
        return new GitHubIntegrationException(
            HttpStatus.BAD_REQUEST,
            "GITHUB_OAUTH_STATE_INVALID",
            "GitHub OAuth state is invalid or expired");
    }
}
