package firefly.github.service;

import firefly.github.dao.GitHubConnectionRepository;
import firefly.github.dto.GitHubConnectionResponse;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubConnectionEntity;
import firefly.github.model.GitHubConnectionStatus;
import firefly.github.oauth.GitHubOAuthResult;
import firefly.github.security.EncryptedSecret;
import firefly.github.security.GitHubSecretCipher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class GitHubConnectionWriter {

    static final String SINGLETON_KEY = "DEFAULT";
    private final GitHubConnectionRepository connectionRepository;
    private final GitHubSecretCipher secretCipher;
    private final Clock clock;

    public GitHubConnectionWriter(
            GitHubConnectionRepository connectionRepository,
            GitHubSecretCipher secretCipher,
            Clock clock
    ) {
        this.connectionRepository = connectionRepository;
        this.secretCipher = secretCipher;
        this.clock = clock;
    }

    @Transactional
    public GitHubConnectionResponse save(GitHubOAuthResult result) {
        GitHubConnectionEntity connection = connectionRepository
                .findBySingletonKey(SINGLETON_KEY)
                .orElseGet(GitHubConnectionEntity::new);
        if (connection.getId() != null
                && !connection.getGithubUserId().equals(result.user().id())) {
            throw new GitHubIntegrationException(
                    HttpStatus.CONFLICT,
                    "GITHUB_CONNECTION_ALREADY_EXISTS",
                    "A different GitHub user is already connected"
            );
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        EncryptedSecret encrypted = secretCipher.encrypt(result.token().accessToken());
        if (connection.getId() == null) {
            connection.setPublicId(UUID.randomUUID().toString())
                    .setSingletonKey(SINGLETON_KEY)
                    .setCreatedAt(now);
        }
        GitHubConnectionStatus nextStatus = connection.getStatus()
                == GitHubConnectionStatus.DISCONNECTING
                ? GitHubConnectionStatus.DISCONNECTING
                : GitHubConnectionStatus.ACTIVE;
        connection.setGithubUserId(result.user().id())
                .setGithubLogin(result.user().login())
                .setAccessTokenCiphertext(encrypted.ciphertext())
                .setTokenNonce(encrypted.nonce())
                .setEncryptionKeyVersion(encrypted.keyVersion())
                .setScopes(result.token().scope() == null ? "" : result.token().scope())
                .setStatus(nextStatus)
                .setLastValidatedAt(now)
                .setUpdatedAt(now);
        return response(connectionRepository.saveAndFlush(connection));
    }

    public GitHubConnectionResponse response(GitHubConnectionEntity connection) {
        List<String> scopes = connection.getScopes() == null || connection.getScopes().isBlank()
                ? List.of()
                : Arrays.stream(connection.getScopes().split(","))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .toList();
        return new GitHubConnectionResponse(
                connection.getPublicId(),
                connection.getGithubUserId(),
                connection.getGithubLogin(),
                connection.getStatus(),
                scopes
        );
    }
}
