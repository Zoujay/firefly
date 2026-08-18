package firefly.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import firefly.github.config.GitHubStorageProperties;
import firefly.github.security.EncryptedSecret;
import firefly.github.security.GitHubSecretCipher;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

class GitHubSecretCipherTests {

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        GitHubStorageProperties properties = new GitHubStorageProperties();
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setKeyVersion("test-v1");
        GitHubSecretCipher cipher = new GitHubSecretCipher(properties, new SecureRandom());

        EncryptedSecret first = cipher.encrypt("github-token");
        EncryptedSecret second = cipher.encrypt("github-token");

        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertEquals(
            "github-token",
            cipher.decrypt(first.ciphertext(), first.nonce(), first.keyVersion()));
    }
}
