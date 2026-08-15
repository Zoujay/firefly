package firefly.github.webhook;

import firefly.github.http.GitHubIntegrationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubWebhookSignatureVerifierTests {

    private final GitHubWebhookSignatureVerifier verifier =
            new GitHubWebhookSignatureVerifier();

    @Test
    void acceptsGitHubOfficialHmacVector() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> verifier.verify(
                payload,
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                "It's a Secret to Everybody"
        ));
    }

    @Test
    void rejectsMalformedAndMismatchedSignatures() {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                GitHubIntegrationException.class,
                () -> verifier.verify(payload, "sha256=not-hex", "secret")
        );
        assertThrows(
                GitHubIntegrationException.class,
                () -> verifier.verify(
                        payload,
                        "sha256=0000000000000000000000000000000000000000000000000000000000000000",
                        "secret"
                )
        );
    }
}
