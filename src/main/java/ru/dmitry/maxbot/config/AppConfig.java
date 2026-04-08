package ru.dmitry.maxbot.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record AppConfig(
        String token,
        String apiBaseUrl,
        Path sqlitePath,
        Set<Long> initialAdminIds,
        int pollTimeoutSeconds,
        int pollLimit
) {
    public static AppConfig fromEnv() {
        String token = required("MAX_BOT_TOKEN");
        String apiBaseUrl = getenv("MAX_API_BASE_URL", "https://platform-api.max.ru");
        Path sqlitePath = Path.of(getenv("SQLITE_PATH", "./data/bot.db")).toAbsolutePath().normalize();
        Set<Long> initialAdminIds = Arrays.stream(getenv("INITIAL_ADMIN_IDS", "").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
        int pollTimeoutSeconds = Integer.parseInt(getenv("POLL_TIMEOUT_SECONDS", "30"));
        int pollLimit = Integer.parseInt(getenv("POLL_LIMIT", "100"));
        return new AppConfig(token, apiBaseUrl, sqlitePath, initialAdminIds, pollTimeoutSeconds, pollLimit);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return value.trim();
    }

    private static String getenv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
