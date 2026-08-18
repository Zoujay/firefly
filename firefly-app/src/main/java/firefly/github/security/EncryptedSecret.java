package firefly.github.security;

public record EncryptedSecret(String ciphertext, byte[] nonce, String keyVersion) {}
