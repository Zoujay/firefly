package firefly.github.config;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({GitHubStorageProperties.class, GitHubProcessingProperties.class})
public class GitHubAppConfiguration {

  @Bean
  public Clock gitHubClock() {
    return Clock.systemUTC();
  }

  @Bean
  public SecureRandom gitHubSecureRandom() {
    return new SecureRandom();
  }
}
