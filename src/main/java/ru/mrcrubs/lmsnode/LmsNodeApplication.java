package ru.mrcrubs.lmsnode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LmsNodeApplication {

    public static void main(String[] args) {
        // Must precede Spring/network-client initialization; HttpClient caches this setting.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        SpringApplication.run(LmsNodeApplication.class, args);
    }

}
