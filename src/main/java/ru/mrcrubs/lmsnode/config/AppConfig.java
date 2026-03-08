package ru.mrcrubs.lmsnode.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({NodeProperties.class, HmacAuthProperties.class})
public class AppConfig {
}
