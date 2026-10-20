package ai.jobmatcher.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CryptoConfig {

    @Bean
    FieldCrypto fieldCrypto(@Value("${app.crypto.key}") String key) {
        return new FieldCrypto(key);
    }
}
