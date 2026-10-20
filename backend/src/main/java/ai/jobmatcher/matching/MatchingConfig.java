package ai.jobmatcher.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchWeights.class)
class MatchingConfig {
}
