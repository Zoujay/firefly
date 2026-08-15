package firefly.github.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("firefly.github.processing")
public class GitHubProcessingProperties {

    private Duration leaseTimeout = Duration.ofMinutes(5);
    private Duration retryDelay = Duration.ofSeconds(30);
    private int maxAttempts = 5;

    public Duration getLeaseTimeout() {
        return leaseTimeout;
    }

    public void setLeaseTimeout(Duration leaseTimeout) {
        this.leaseTimeout = leaseTimeout;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
