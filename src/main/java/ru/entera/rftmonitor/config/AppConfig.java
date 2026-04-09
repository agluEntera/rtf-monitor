package ru.entera.rftmonitor.config;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import java.net.URI;
import java.util.List;

/**
 * Application configuration loaded from .env file and environment variables.
 */
@Getter
public final class AppConfig {

    //region Constants

    public static final List<String> MONITORED_STATUSES = List.of(
        "Ready for Testing",
        "Ready for Review",
        "In Testing"
    );

    private static final String DEFAULT_JIRA_URL = "https://entera.atlassian.net";
    private static final int DEFAULT_THRESHOLD = 4;
    private static final int DEFAULT_TARGET_PERCENT = 70;
    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final int DEFAULT_MATTERMOST_PORT = 8080;

    //endregion

    //region Fields

    private final String jiraUrl;
    private final String jiraEmail;
    private final String jiraApiToken;
    private final String telegramBotToken;
    private final String telegramChatId;
    private final String mysqlHost;
    private final int mysqlPort;
    private final String mysqlDb;
    private final String mysqlUser;
    private final String mysqlPassword;
    private final int thresholdBusinessDays;
    private final int targetPercent;
    private final String proxyHost;
    private final int proxyPort;
    private final boolean mattermostEnabled;
    private final int mattermostPort;
    private final String mattermostToken;

    //endregion

    //region Ctor

    /**
     * Loads all configuration values from .env file and environment variables.
     * Proxy is read from the {@code https_proxy} environment variable if present.
     */
    public AppConfig() {

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        this.jiraUrl = dotenv.get("JIRA_URL", DEFAULT_JIRA_URL);
        this.jiraEmail = dotenv.get("JIRA_EMAIL");
        this.jiraApiToken = dotenv.get("JIRA_API_TOKEN");
        this.telegramBotToken = dotenv.get("TELEGRAM_BOT_TOKEN");
        this.telegramChatId = dotenv.get("TELEGRAM_CHAT_ID");
        this.mysqlHost = dotenv.get("MYSQL_SERVER");
        this.mysqlPort = Integer.parseInt(dotenv.get("MYSQL_PORT", String.valueOf(DEFAULT_MYSQL_PORT)));
        this.mysqlDb = dotenv.get("MYSQL_DBNAME");
        this.mysqlUser = dotenv.get("MYSQL_USERNAME");
        this.mysqlPassword = dotenv.get("MYSQL_PASSWORD");
        this.thresholdBusinessDays = Integer.parseInt(
            dotenv.get("THRESHOLD_BUSINESS_DAYS", String.valueOf(DEFAULT_THRESHOLD))
        );
        this.targetPercent = Integer.parseInt(
            dotenv.get("TARGET_PERCENT", String.valueOf(DEFAULT_TARGET_PERCENT))
        );

        this.mattermostEnabled = Boolean.parseBoolean(dotenv.get("MATTERMOST_ENABLED", "false"));
        this.mattermostPort = Integer.parseInt(dotenv.get("MATTERMOST_PORT", String.valueOf(DEFAULT_MATTERMOST_PORT)));
        this.mattermostToken = dotenv.get("MATTERMOST_TOKEN", "");

        String rawProxy = System.getenv("https_proxy");

        if (rawProxy != null && !rawProxy.isEmpty()) {
            URI proxyUri = URI.create(rawProxy);
            this.proxyHost = proxyUri.getHost();
            this.proxyPort = proxyUri.getPort();
        } else {
            this.proxyHost = null;
            this.proxyPort = 0;
        }
    }

    //endregion

    //region Public

    /**
     * @return {@code true} if proxy is configured
     */
    public boolean hasProxy() {

        return this.proxyHost != null;
    }

    //endregion
}
