package ru.entera.rftmonitor.bot;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
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
 */
public final class MonitorBot extends TelegramLongPollingBot {

    //region Constants

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    //endregion

    //region Fields

    private final AppConfig config;
    private final IssueService issueService;
    private final MessageBuilder messageBuilder;

    //endregion

    //region Ctor

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

    public void registerCommands() {

        SetMyCommands setMyCommands = SetMyCommands.builder()
            .commands(List.of(
                BotCommand.builder().command("status").description("Полный отчёт по всем статусам").build(),
                BotCommand.builder().command("review").description("Отчёт: Ready for Review + Under Review").build(),
                BotCommand.builder().command("testing").description("Отчёт: Ready for Testing + In Testing").build(),
                BotCommand.builder().command("stale").description("Зависшие задачи по категориям").build(),
                BotCommand.builder().command("history").description("Историческая статистика по статусам").build(),
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

        if (update.hasCallbackQuery()) {
            this.handleCallbackQuery(update.getCallbackQuery());
            return;
        }

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

    //region Private — command handlers

    private void handleHelp(long chatId) {

        this.send(chatId,
            "<b>RFT Monitor</b> — мониторинг задач в тестировании\n\n"
            + "/status — полный отчёт по всем статусам\n"
            + "/review — отчёт: Ready for Review + Under Review\n"
            + "/testing — отчёт: Ready for Testing + In Testing\n"
            + "/stale — зависшие задачи по категориям\n"
            + "/history — историческая статистика (кнопки выбора)"
        );
    }

    private void handleStatus(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            List<Issue> issues = this.issueService.getIssues();
            this.send(chatId, this.messageBuilder.buildFullReport(issues, Map.of()));
            this.send(chatId, this.messageBuilder.buildStaleReport(this.issueService.getStaleReport()));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleReview(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            List<Issue> issues = this.issueService.getIssues();
            this.send(chatId, this.messageBuilder.buildGroupReport(issues, Map.of(), AppConfig.REVIEW_STATUSES));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleTesting(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            List<Issue> issues = this.issueService.getIssues();
            this.send(chatId, this.messageBuilder.buildGroupReport(issues, Map.of(), AppConfig.TESTING_STATUSES));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void handleStale(long chatId) {

        this.send(chatId, "⏳ Собираю данные...");

        try {
            this.send(chatId, this.messageBuilder.buildStaleReport(this.issueService.getStaleReport()));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    /**
     * /history without args — shows quick-pick inline keyboard.
     * /history with args — parses directly (e.g. /history 01.01.2025 31.03.2025 70).
     */
    private void handleHistory(long chatId, String args) {

        if (!args.isBlank()) {
            this.computeAndSendHistory(chatId, args);
            return;
        }

        // No args — show quick-pick keyboard
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
            .keyboardRow(List.of(
                btn("30 дн · P50",  "hist:30:50"),
                btn("30 дн · P70",  "hist:30:70"),
                btn("30 дн · P90",  "hist:30:90"),
                btn("30 дн · P95",  "hist:30:95")
            ))
            .keyboardRow(List.of(
                btn("3 мес · P50",  "hist:90:50"),
                btn("3 мес · P70",  "hist:90:70"),
                btn("3 мес · P90",  "hist:90:90"),
                btn("3 мес · P95",  "hist:90:95")
            ))
            .keyboardRow(List.of(
                btn("6 мес · P70",  "hist:180:70"),
                btn("1 год · P70",  "hist:365:70"),
                btn("1 год · P90",  "hist:365:90")
            ))
            .build();

        try {
            this.execute(SendMessage.builder()
                .chatId(chatId)
                .text("📊 <b>История статусов</b>\n\nВыберите период и персентиль:")
                .parseMode("HTML")
                .replyMarkup(markup)
                .build());
        } catch (TelegramApiException e) {
            System.err.println("[Telegram] Send error: " + e.getMessage());
        }
    }

    //endregion

    //region Private — callback query (inline button press)

    private void handleCallbackQuery(CallbackQuery callbackQuery) {

        String data   = callbackQuery.getData();
        long   chatId = callbackQuery.getMessage().getChatId();

        // Acknowledge immediately — removes the loading spinner on the button
        try {
            this.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.getId())
                .build());
        } catch (TelegramApiException e) {
            // non-critical
        }

        if (data != null && data.startsWith("hist:")) {
            String[] parts = data.split(":");

            int days       = Integer.parseInt(parts[1]);
            int percentile = Integer.parseInt(parts[2]);

            LocalDate to   = LocalDate.now();
            LocalDate from = to.minusDays(days);

            this.send(chatId, "⏳ Собираю данные...");

            try {
                Map<String, Optional<StatusHistoryStat>> stats =
                    this.issueService.getHistoryReport(from, to, percentile);
                this.send(chatId, this.messageBuilder.buildHistoryReport(stats, from, to, percentile));
            } catch (Exception e) {
                this.send(chatId, "❌ Ошибка: " + e.getMessage());
            }
        }
    }

    //endregion

    //region Private — helpers

    private void computeAndSendHistory(long chatId, String args) {

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(3);
        int percentile = 70;

        String[] parts = args.split("\\s+");

        try {
            if (parts.length == 1) {
                percentile = Integer.parseInt(parts[0]);
            } else if (parts.length == 2) {
                from = LocalDate.parse(parts[0], DATE_FMT);
                to   = LocalDate.parse(parts[1], DATE_FMT);
            } else if (parts.length >= 3) {
                from       = LocalDate.parse(parts[0], DATE_FMT);
                to         = LocalDate.parse(parts[1], DATE_FMT);
                percentile = Integer.parseInt(parts[2]);
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            this.send(chatId,
                "❌ Формат: /history [dd.MM.yyyy dd.MM.yyyy [персентиль]]\n"
                + "Пример: /history 01.01.2025 31.03.2025 70");
            return;
        }

        this.send(chatId, "⏳ Собираю данные...");

        try {
            Map<String, Optional<StatusHistoryStat>> stats =
                this.issueService.getHistoryReport(from, to, percentile);
            this.send(chatId, this.messageBuilder.buildHistoryReport(stats, from, to, percentile));
        } catch (Exception e) {
            this.send(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private static InlineKeyboardButton btn(String text, String data) {

        return InlineKeyboardButton.builder()
            .text(text)
            .callbackData(data)
            .build();
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

        try {
            this.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .build());
        } catch (TelegramApiException e) {
            System.err.println("[Telegram] Send error: " + e.getMessage());
        }
    }

    //endregion
}
