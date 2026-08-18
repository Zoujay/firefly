package firefly.github.webhook;

import firefly.github.http.GitHubIntegrationException;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class GitHubWebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    public void verify(byte[] payload, String signature, String secret) {
        if (payload == null
            || !StringUtils.hasText(signature)
            || !StringUtils.hasText(secret)
            || !signature.startsWith(PREFIX)) {
            throw invalidSignature();
        }
        String suppliedHex = signature.substring(PREFIX.length());
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(suppliedHex);
        } catch (IllegalArgumentException exception) {
            throw invalidSignature();
        }
        byte[] expected = digest(payload, secret);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw invalidSignature();
        }
    }

    private byte[] digest(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private GitHubIntegrationException invalidSignature() {
        return new GitHubIntegrationException(
            HttpStatus.FORBIDDEN,
            "GITHUB_WEBHOOK_SIGNATURE_INVALID",
            "GitHub webhook authentication failed");
    }
}
