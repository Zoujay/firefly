package firefly.github.security;

import firefly.github.config.GitHubStorageProperties;
import firefly.github.http.GitHubIntegrationException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class GitHubSecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final GitHubStorageProperties properties;
    private final SecureRandom secureRandom;

    public GitHubSecretCipher(GitHubStorageProperties properties, SecureRandom secureRandom) {
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    public EncryptedSecret encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("Secret plaintext is required");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(
                Base64.getEncoder().encodeToString(ciphertext),
                nonce,
                properties.getKeyVersion());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encrypt GitHub secret", exception);
        }
    }

    public String decrypt(String ciphertext, byte[] nonce, String keyVersion) {
        if (!StringUtils.hasText(ciphertext) || nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalStateException("Encrypted GitHub secret is incomplete");
        }
        if (!properties.getKeyVersion().equals(keyVersion)) {
            throw new IllegalStateException(
                "Unsupported GitHub encryption key version: " + keyVersion);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot decrypt GitHub secret", exception);
        }
    }

    private SecretKeySpec key() {
        if (!StringUtils.hasText(properties.getEncryptionKey())) {
            throw new GitHubIntegrationException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GITHUB_ENCRYPTION_NOT_CONFIGURED",
                "GitHub storage encryption key is not configured");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.getEncryptionKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("GitHub encryption key must be Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("GitHub encryption key must decode to 32 bytes");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
