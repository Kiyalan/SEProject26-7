package com.repopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@SpringBootApplication
public class RepoPilotApplication {

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(RepoPilotApplication.class, args);
    }

    private static void loadDotenv() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String candidate : List.of(".env", "backend/.env")) {
            Path envFile = cwd.resolve(candidate).normalize();
            if (!Files.isRegularFile(envFile)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    if (key.isEmpty() || System.getenv(key) != null) {
                        continue;
                    }
                    System.setProperty(key, value);
                }
            } catch (IOException ignored) {
            }
            return;
        }
    }
}
