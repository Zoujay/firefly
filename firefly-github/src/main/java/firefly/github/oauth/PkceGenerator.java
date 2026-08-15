package firefly.github.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class PkceGenerator {

    private static final int VERIFIER_BYTES = 64;
    private final SecureRandom secureRandom;

    public PkceGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public PkcePair create() {
        byte[] random = new byte[VERIFIER_BYTES];
        secureRandom.nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return new PkcePair(verifier, challenge);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
