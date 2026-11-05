package ru.mrcrubs.lmsnode.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.mrcrubs.lmsnode.infra.FileJobRepository;
import ru.mrcrubs.lmsnode.infra.InMemoryJobRepository;
import ru.mrcrubs.lmsnode.infra.JobRepository;
import ru.mrcrubs.lmsnode.infra.PostgresJobRepository;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties({NodeProperties.class, HmacAuthProperties.class})
public class AppConfig {

    @Bean
    public JobRepository jobRepository(NodeProperties nodeProperties) {
        if (System.getenv("DATABASE_URL_FILE") != null) return new PostgresJobRepository();
        String stateFile = nodeProperties.getStateFile();
        if (stateFile == null || stateFile.isBlank()) {
            return new InMemoryJobRepository();
        }
        return new FileJobRepository(Path.of(stateFile.strip()));
    }
}
