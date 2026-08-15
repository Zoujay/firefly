package firefly.github.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("firefly.github.storage")
public class GitHubStorageProperties {

    private String encryptionKey = "";
    private String keyVersion = "v1";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(String keyVersion) {
        this.keyVersion = keyVersion;
    }
}
