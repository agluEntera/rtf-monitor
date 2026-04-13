package ru.entera.rftmonitor.bot;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import ru.entera.rftmonitor.model.StaleReport;
import ru.entera.rftmonitor.model.StatusHistoryStat;
import ru.entera.rftmonitor.service.IssueService;
import ru.entera.rftmonitor.service.MessageBuilder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Telegram long-polling bot for RFT monitoring.
 * <p>
 * Supported commands:
 * <ul>
 *   <li>{@code /start}, {@code /help} — command list</li>
 *   <li>{@code /status} — full report by status</li>
 *   <li>{@code /review} — report for Ready for Review + Under Review</li>
 *   <li>{@code /testing} — report for Ready for Testing + In Testing</li>
 *   <li>{@code /stale} — stale issues by category</li>
 * </ul>
 */
public final class MonitorBot extends TelegramLongPollingBot {

    //region Fields

    private final AppConfig config;
    private final IssueService issueService;
    private final MessageBuilder messageBuilder;

    //endregion

    //region Ctor

    /**
     * @param options        bot options (proxy, timeouts)
     * @param config         application configuration
     * @param issueService   service for fetching and enriching issues
     * @param messageBuilder service for building Telegram messages
     */
    public MonitorBot(DefaultBotOptions options, AppConfig config, IssueService issueService, MessageBuilder messageBuilder) {

        super(options, config.getTelegramBotToken());
        this.config = config;
        this.issueService = issueService;
        this.messageBuilder = messageBuilder;
    }

    //endregion

    //region Public

    @Override
    public String getBotUsername() {

        return "enterajirabot";
    }

    /**
     * Registers bot commands so they appear in the Telegram menu (the "/" button).
     * Call once after the bot is registered with {@link org.telegram.telegrambots.meta.TelegramBotsApi}.
     */
    public void registerCommands() {

        SetMyCommands setMyCommands = SetMyCommands.builder()
            .commands(List.of(
                BotCommand.builder().command("status").description("Полный отчёт по всем статусам").build(),
                BotCommand.builder().command("review").description("Отчёт: Ready for Review + Under Review").build(),
                BotCommand.builder().command("testing").description("Отчёт: Ready for Testing + In Testing").build(),
                BotCommand.builder().command("stale").description("Зависшие задачи по категориям").build(),
                BotCommand.builder().command("history").description("История: /history [dd.MM.yyyy dd.MM.yyyy [%]]").build(),
                BotCommand.builder().command("help").description("Список команд").build()
            ))
            .build();

        try {
            this.execute(setMyCommands);
            System.out.println("Команды бота зарегистрированы.");
        } catch (TelegramApiException e) {
            System.err.println("[Telegram] Ошибка регистрации команд: " + e.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();

        if (text.startsWith("/start") || text.startsWith("/help")) {
            this.handleHelp(chatId);
        } else if (text.startsWith("/status")) {
            this.handleStatus(chatId);
        } else if (text.startsWith("/review")) {
            this.handleReview(chatId);
        } else if (text.startsWith("/testing")) {
            this.handleTesting(chatId);
        } else if (text.startsWith("/stale")) {
            this.handleStale(chatId);
        } else if (text.startsWith("/history")) {
            String args = text.substring("/history".length()).trim();
            this.handleHistory(chatId, args);
        }
    }

    //endregion

    //region Private

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private void handleHelp(long chatId) {

        this.send(chatId,
            "<b>RFT Monitor</b> — мониторинг задач в тестировании\n\n"
            + "/status — полный отчёт по всем статусам\n"
            + "/review — отчёт: Ready for Review + Under Review\n"
            + "/testing — отчёт: Ready for Testing + In Testing\n"
            + "/stale — зависшие задачи по категориям\n"
            + "/history [dd.MM.yyyy dd.MM.yyyy [персентиль]] — историческая статистика\n"
            + "  Пример: /history 01.01.2025 31.03.2025 70"
        );
    }

    private void handleStatus(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            List<Issue> issues = this.issueService.getIssues();
            Map<String, OptionalDouble> p70ByStatus = this.buildP70Map();
            String fullReport = this.messageBuilder.buildFullReport(issues, p70ByStatus);

            this.send(chatId, fullReport);

            StaleReport staleReport = this.issueService.getStaleReport();
            String staleMessage = this.messageBuilder.buildStaleReport(staleReport);

            this.send(chatId, staleMessage);
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleReview(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            List<Issue> issues = this.issueService.getIssues();
            Map<String, OptionalDouble> p70ByStatus = this.buildP70Map();
            this.send(chatId, this.messageBuilder.buildGroupReport(issues, p70ByStatus, AppConfig.REVIEW_STATUSES));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleTesting(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            List<Issue> issues = this.issueService.getIssues();
            Map<String, OptionalDouble> p70ByStatus = this.buildP70Map();
            this.send(chatId, this.messageBuilder.buildGroupReport(issues, p70ByStatus, AppConfig.TESTING_STATUSES));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleStale(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            StaleReport report = this.issueService.getStaleReport();
            String message = this.messageBuilder.buildStaleReport(report);

            this.send(chatId, message);
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleHistory(long chatId, String args) {

        this.send(chatId, "⏳ Собираю данные...");

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(3);
        int percentile = 70;

        String[] parts = args.isBlank() ? new String[0] : args.split("\\s+");

        try {
            if (parts.length == 1) {
                percentile = Integer.parseInt(parts[0]);
            } else if (parts.length >= 3) {
                from = LocalDate.parse(parts[0], DATE_FMT);
                to = LocalDate.parse(parts[1], DATE_FMT);
                percentile = Integer.parseInt(parts[2]);
            } else if (parts.length == 2) {
                from = LocalDate.parse(parts[0], DATE_FMT);
                to = LocalDate.parse(parts[1], DATE_FMT);
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            this.send(chatId, "❌ Формат: /history [dd.MM.yyyy dd.MM.yyyy [персентиль]]\nПример: /history 01.01.2025 31.03.2025 70");

            return;
        }

        try {
            Map<String, Optional<StatusHistoryStat>> stats = this.issueService.getHistoryReport(from, to, percentile);
            this.send(chatId, this.messageBuilder.buildHistoryReport(stats, from, to, percentile));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void send(long chatId, String text) {

        if (text.length() <= 4096) {
            this.sendRaw(chatId, text);

            return;
        }

        String[] lines = text.split("\n", -1);
        StringBuilder chunk = new StringBuilder();

        for (String line : lines) {
            String candidate = chunk.isEmpty() ? line : chunk + "\n" + line;

            if (candidate.length() > 4096) {
                if (!chunk.isEmpty()) {
                    this.sendRaw(chatId, chunk.toString());
                }
                // Строка сама по себе длиннее лимита — режем посимвольно
                String remaining = line;
                while (remaining.length() > 4096) {
                    this.sendRaw(chatId, remaining.substring(0, 4096));
                    remaining = remaining.substring(4096);
                }
                chunk = new StringBuilder(remaining);
            } else {
                chunk = new StringBuilder(candidate);
            }
        }

        if (!chunk.isEmpty()) {
            this.sendRaw(chatId, chunk.toString());
        }
    }

    private void sendRaw(long chatId, String text) {

        SendMessage message = SendMessage.builder()
            .chatId(chatId)
            .text(text)
            .parseMode("HTML")
            .disableWebPagePreview(true)
            .build();

        try {
            this.execute(message);
        } catch (TelegramApiException e) {
            System.err.println("[Telegram] Send error: " + e.getMessage());
        }
    }

    //endregion
}
