package ru.entera.rftmonitor.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.entera.rftmonitor.client.MattermostApiClient;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import ru.entera.rftmonitor.model.StaleReport;
import ru.entera.rftmonitor.model.StatusHistoryStat;
import ru.entera.rftmonitor.service.IssueService;
import ru.entera.rftmonitor.service.MattermostMessageBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Mattermost slash-command bot.
 * <p>
 * Запускает HTTP-сервер, который принимает POST-запросы от Mattermost
 * при вводе slash-команд и возвращает отчёт в формате Markdown.
 * <p>
 * Поддерживаемые команды:
 * <ul>
 *   <li>{@code /rft-status}  — полный отчёт по всем статусам</li>
 *   <li>{@code /rft-review}  — Ready for Review + Under Review</li>
 *   <li>{@code /rft-testing} — Ready for Testing + In Testing</li>
 *   <li>{@code /rft-stale}   — зависшие задачи по категориям</li>
 *   <li>{@code /rft-history} — интерактивный диалог: история статусов</li>
 * </ul>
 */
public final class MattermostBot {

    //region Constants

    private static final String ENDPOINT        = "/mattermost";
    private static final String DIALOG_ENDPOINT = "/mattermost/dialog";
    private static final String RESPONSE_TYPE_IN_CHANNEL = "in_channel";
    private static final String RESPONSE_TYPE_EPHEMERAL  = "ephemeral";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    //endregion

    //region Fields

    private final AppConfig config;
    private final IssueService issueService;
    private final MattermostMessageBuilder messageBuilder;
    private final ObjectMapper objectMapper;
    private HttpServer server;
    private java.util.Set<String> validTokens = new java.util.HashSet<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    //endregion

    //region Ctor

    public MattermostBot(AppConfig config, IssueService issueService, MattermostMessageBuilder messageBuilder) {

        this.config = config;
        this.issueService = issueService;
        this.messageBuilder = messageBuilder;
        this.objectMapper = new ObjectMapper();
    }

    //endregion

    //region Public

    public void start() throws IOException {

        this.validTokens = new MattermostApiClient(this.config).registerCommands();

        this.server = HttpServer.create(new InetSocketAddress(this.config.getMattermostPort()), 0);
        this.server.createContext(ENDPOINT, this::handleRequest);
        this.server.createContext(DIALOG_ENDPOINT, this::handleDialogSubmission);
        this.server.start();
        System.out.println("Mattermost Bot слушает на порту " + this.config.getMattermostPort() + ENDPOINT);
    }

    public void stop() {

        if (this.server != null) {
            this.server.stop(0);
        }
    }

    //endregion

    //region Private — request routing

    private void handleRequest(HttpExchange exchange) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            this.sendResponse(exchange, 405, this.errorJson("Method not allowed"));
            return;
        }

        Map<String, String> params = this.parseBody(exchange);

        if (!this.isTokenValid(params.get("token"))) {
            this.sendResponse(exchange, 401, this.errorJson("Invalid token"));
            return;
        }

        String command = params.getOrDefault("command", "");

        // /rft-history opens an interactive modal dialog.
        // trigger_id is valid only for 3 seconds, so open the dialog synchronously
        // before sending the HTTP response.
        if ("/rft-history".equals(command)) {
            this.openHistoryDialog(params);
            this.sendResponse(exchange, 200, "{}");
            return;
        }

        String text = params.getOrDefault("text", "").trim();
        String responseUrl = params.getOrDefault("response_url", "");

        // Immediately acknowledge — prevents timeout in Mattermost UI
        this.sendResponse(exchange, 200, this.responseJson(RESPONSE_TYPE_EPHEMERAL, "⏳ Выполняется..."));

        this.executor.submit(() -> {
            String result = this.route(command, text);
            this.sendDelayed(responseUrl, result);
        });
    }

    private String route(String command, String args) {

        try {
            String text = switch (command) {
                case "/rft-status" -> {
                    List<Issue> issues = this.issueService.getIssues();
                    yield this.messageBuilder.buildFullReport(issues, Map.of());
                }
                case "/rft-review" -> {
                    List<Issue> issues = this.issueService.getIssues();
                    yield this.messageBuilder.buildGroupReport(issues, Map.of(), AppConfig.REVIEW_STATUSES);
                }
                case "/rft-testing" -> {
                    List<Issue> issues = this.issueService.getIssues();
                    yield this.messageBuilder.buildGroupReport(issues, Map.of(), AppConfig.TESTING_STATUSES);
                }
                case "/rft-stale" -> this.messageBuilder.buildStaleReport(this.issueService.getStaleReport());
                default -> "Неизвестная команда: " + command;
            };

            return this.responseJson(RESPONSE_TYPE_IN_CHANNEL, text);
        } catch (Exception e) {
            return this.responseJson(RESPONSE_TYPE_EPHEMERAL, "❌ Ошибка: " + e.getMessage());
        }
    }

    //endregion

    //region Private — interactive dialog for /rft-history

    /**
     * Opens a Mattermost interactive modal dialog for the history command.
     * Uses {@code trigger_id} from the slash-command POST; valid for 3 seconds.
     * The {@code response_url} is passed as dialog state so the submission handler
     * can POST the result back to the correct channel.
     */
    private void openHistoryDialog(Map<String, String> params) {

        String triggerId = params.getOrDefault("trigger_id", "");
        String responseUrl = params.getOrDefault("response_url", "");

        if (triggerId.isBlank() || this.config.getMattermostBotUrl().isBlank()) {
            System.err.println("[Mattermost] Cannot open dialog: trigger_id or botUrl is blank");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate defaultFrom = today.minusMonths(3);

        try {
            ObjectNode payload = this.objectMapper.createObjectNode();
            payload.put("trigger_id", triggerId);
            payload.put("url", this.config.getMattermostBotUrl() + DIALOG_ENDPOINT);

            ObjectNode dialog = this.objectMapper.createObjectNode();
            dialog.put("title", "История статусов");
            dialog.put("submit_label", "Показать");
            dialog.put("state", responseUrl);

            ArrayNode elements = this.objectMapper.createArrayNode();

            elements.add(this.textElement("from_date", "Дата начала", "дд.ММ.гггг", defaultFrom.format(DATE_FMT)));
            elements.add(this.textElement("to_date",   "Дата конца",  "дд.ММ.гггг", today.format(DATE_FMT)));

            ObjectNode pctEl = this.objectMapper.createObjectNode();
            pctEl.put("type", "select");
            pctEl.put("name", "percentile");
            pctEl.put("display_name", "Персентиль");
            pctEl.put("default", "70");

            ArrayNode options = this.objectMapper.createArrayNode();
            this.addOption(options, "P50 — медиана",          "50");
            this.addOption(options, "P70 (рекомендуется)",    "70");
            this.addOption(options, "P90",                    "90");
            this.addOption(options, "P95",                    "95");
            pctEl.set("options", options);
            elements.add(pctEl);

            dialog.set("elements", elements);
            payload.set("dialog", dialog);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.config.getMattermostUrl() + "/api/v4/actions/dialogs/open"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + this.config.getMattermostToken())
                .POST(HttpRequest.BodyPublishers.ofString(
                    this.objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

            this.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[Mattermost] Failed to open history dialog: " + e.getMessage());
        }
    }

    /**
     * Handles the form submission from the history dialog.
     * Validates inputs, computes stats, and POSTs the result to the channel via response_url.
     */
    private void handleDialogSubmission(HttpExchange exchange) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String bodyStr = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        JsonNode body;
        try {
            body = this.objectMapper.readTree(bodyStr);
        } catch (Exception e) {
            this.sendResponse(exchange, 400, "{}");
            return;
        }

        JsonNode submission = body.path("submission");
        String responseUrl  = body.path("state").asText("");

        String fromStr = submission.path("from_date").asText("").trim();
        String toStr   = submission.path("to_date").asText("").trim();
        String pctStr  = submission.path("percentile").asText("70");

        // Validate — return field-level errors directly in the dialog
        LocalDate from, to;
        try {
            from = LocalDate.parse(fromStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            this.sendResponse(exchange, 200,
                "{\"errors\":{\"from_date\":\"Неверный формат — используйте дд.ММ.гггг\"}}");
            return;
        }

        try {
            to = LocalDate.parse(toStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            this.sendResponse(exchange, 200,
                "{\"errors\":{\"to_date\":\"Неверный формат — используйте дд.ММ.гггг\"}}");
            return;
        }

        int percentile;
        try {
            percentile = Integer.parseInt(pctStr);
        } catch (NumberFormatException e) {
            percentile = 70;
        }

        // Close the dialog
        this.sendResponse(exchange, 200, "{}");

        // Compute and post result asynchronously
        final LocalDate f = from, t = to;
        final int p = percentile;

        this.executor.submit(() -> {
            try {
                Map<String, Optional<StatusHistoryStat>> stats = this.issueService.getHistoryReport(f, t, p);
                String text = this.messageBuilder.buildHistoryReport(stats, f, t, p);
                this.sendDelayed(responseUrl, this.responseJson(RESPONSE_TYPE_IN_CHANNEL, text));
            } catch (Exception e) {
                this.sendDelayed(responseUrl,
                    this.responseJson(RESPONSE_TYPE_EPHEMERAL, "❌ Ошибка: " + e.getMessage()));
            }
        });
    }

    //endregion

    //region Private — helpers

    private ObjectNode textElement(String name, String displayName, String placeholder, String defaultVal) {

        ObjectNode el = this.objectMapper.createObjectNode();
        el.put("type", "text");
        el.put("name", name);
        el.put("display_name", displayName);
        el.put("placeholder", placeholder);
        el.put("default", defaultVal);

        return el;
    }

    private void addOption(ArrayNode options, String text, String value) {

        ObjectNode opt = this.objectMapper.createObjectNode();
        opt.put("text", text);
        opt.put("value", value);
        options.add(opt);
    }

    private void sendDelayed(String responseUrl, String json) {

        if (responseUrl == null || responseUrl.isEmpty()) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            this.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[Mattermost] Failed to send delayed response: " + e.getMessage());
        }
    }

    private boolean isTokenValid(String token) {

        return this.validTokens.contains(token);
    }

    private Map<String, String> parseBody(HttpExchange exchange) throws IOException {

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        return Arrays.stream(body.split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(
                arr -> URLDecoder.decode(arr[0], StandardCharsets.UTF_8),
                arr -> arr.length > 1 ? URLDecoder.decode(arr[1], StandardCharsets.UTF_8) : "",
                (a, b) -> a
            ));
    }

    private String responseJson(String responseType, String text) {

        ObjectNode node = this.objectMapper.createObjectNode();
        node.put("response_type", responseType);
        node.put("text", text);

        try {
            return this.objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"response_type\":\"ephemeral\",\"text\":\"Serialization error\"}";
        }
    }

    private String errorJson(String message) {

        return this.responseJson(RESPONSE_TYPE_EPHEMERAL, "❌ " + message);
    }

    private void sendResponse(HttpExchange exchange, int code, String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    //endregion
}
