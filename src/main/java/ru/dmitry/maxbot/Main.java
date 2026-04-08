package ru.dmitry.maxbot;

import ru.dmitry.maxbot.api.MaxBotApiClient;
import ru.dmitry.maxbot.config.AppConfig;
import ru.dmitry.maxbot.db.Database;
import ru.dmitry.maxbot.service.BotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        Database database = new Database(config.sqlitePath(), config.initialAdminIds());
        MaxBotApiClient apiClient = new MaxBotApiClient(config);
        BotService botService = new BotService(config, database, apiClient);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> log.info("MAX bot is stopping")));
        botService.run();
    }
}
