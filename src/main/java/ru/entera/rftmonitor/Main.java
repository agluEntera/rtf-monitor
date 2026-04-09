package ru.entera.rftmonitor;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.entera.rftmonitor.bot.MonitorBot;
import ru.entera.rftmonitor.client.JiraApiClient;
import ru.entera.rftmonitor.client.MySqlRepository;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.service.IssueService;
import ru.entera.rftmonitor.service.MessageBuilder;

/**
 * Application entry point.
 */
public final class Main {

    //region Ctor

    private Main() {}

    //endregion

    //region Public static

    /**
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {

        AppConfig config = new AppConfig();

        if (config.hasProxy()) {
            System.setProperty("https.proxyHost", config.getProxyHost());
            System.setProperty("https.proxyPort", String.valueOf(config.getProxyPort()));
            System.setProperty("http.proxyHost", config.getProxyHost());
            System.setProperty("http.proxyPort", String.valueOf(config.getProxyPort()));
            System.setProperty("http.nonProxyHosts", "localhost|127.*");
            System.out.println("Proxy configured: " + config.getProxyHost() + ":" + config.getProxyPort());
        }

        JiraApiClient jiraApiClient = new JiraApiClient(config);
        MySqlRepository mySqlRepository = new MySqlRepository(config);
        IssueService issueService = new IssueService(config, jiraApiClient, mySqlRepository);
        MessageBuilder messageBuilder = new MessageBuilder(config);
        MonitorBot bot = new MonitorBot(config, issueService, messageBuilder);

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            System.out.println("RFT Monitor Bot запущен.");
        } catch (TelegramApiException e) {
            System.err.println("Failed to start bot: " + e.getMessage());
            System.exit(1);
        }
    }

    //endregion
}
