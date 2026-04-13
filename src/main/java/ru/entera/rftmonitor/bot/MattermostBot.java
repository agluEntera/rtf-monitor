package ru.entera.rftmonitor.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.entera.rftmonitor.client.MattermostApiClient;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import ru.entera.rftmonitor.model.StaleReport;
import ru.entera.rftmonitor.service.IssueService;
import ru.entera.rftmonitor.service.MattermostMessageBuilder;
import ru.entera.rftmonitor.model.StatusHistoryStat;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Mattermost slash-command bot.
 * <p>
 * Запускает HTTP-сервер, который принимает POST-запросы от Mattermost
 * при вводе slash-команд и возвращает отчёт в формате Markdown.
 * <p>
 * Поддерживаемые команды (настраиваются в Mattermost):
 * <ul>
 *   <li>{@code /rft-status}  — полный отчёт по всем статусам</li>
 *   <li>{@code /rft-review}  — отчёт: Ready for Review + Under Review</li>
 *   <li>{@code /rft-testing} — отчёт: Ready for Testing + In Testing</li>
 *   <li>{@code /rft-stale}   — зависшие задачи по категориям</li>
 * </ul>
 * <p>
 * Для активации установи {@code MATTERMOST_ENABLED=true} в .env.
 * Инструкция по подключению: {@code Mattermost — инструкция.md}.
 */
public final class MattermostBot {

    //region Constants

    private static final String ENDPOINT = "/mattermost";
    private static final String RESPONSE_TYPE_IN_CHANNEL = "in_channel";
    private static final String RESPONSE_TYPE_EPHEMERAL = "ephemeral";

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

    /**
     * @param config         application configuration
     * @param issueService   service for fetching and enriching issues
     * @param messageBuilder service for building Mattermost Markdown messages
     */
    public MattermostBot(AppConfig config, IssueService issueService, MattermostMessageBuilder messageBuilder) {

        this.config = config;
        this.issueService = issueService;
        this.messageBuilder = messageBuilder;
        this.objectMapper = new ObjectMapper();
    }

    //endregion

    //region Public

    /**
     * Starts the HTTP server and begins accepting slash-command requests from Mattermost.
     *
     * @throws IOException if the server cannot bind to the configured port
     */
    public void start() throws IOException {

        this.validTokens = new MattermostApiClient(this.config).registerCommands();

        this.server = HttpServer.create(new InetSocketAddress(this.config.getMattermostPort()), 0);
        this.server.createContext(ENDPOINT, this::handleRequest);
        this.server.start();
        System.out.println("Mattermost Bot слушает на порту " + this.config.getMattermostPort() + ENDPOINT);
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() {

        if (this.server != null) {
            this.server.stop(0);
        }
    }

    //endregion

    //region Private

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
        String text = params.getOrDefault("text", "").trim();
        String responseUrl = params.getOrDefault("response_url", "");

        // Immediately acknowledge — prevents timeout in Mattermost UI
        this.sendResponse(exchange, 200, this.responseJson(RESPONSE_TYPE_EPHEMERAL, "⏳ Выполняется..."));

        // Process in background and send result to response_url
        this.executor.submit(() -> {
            String result = this.route(command, text);
            this.sendDelayed(responseUrl, result);
        });
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private String route(String command, String args) {

        try {
            String responseText = switch (command) {
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
                case "/rft-history" -> this.handleHistory(args);
                default -> "Неизвестная команда. Доступны: /rft-status, /rft-review, /rft-testing, /rft-stale, /rft-history";
            };

            return this.responseJson(RESPONSE_TYPE_IN_CHANNEL, responseText);
        } catch (Exception e) {
            return this.responseJson(RESPONSE_TYPE_EPHEMERAL, "❌ Ошибка: " + e.getMessage());
        }
    }

    private String handleHistory(String args) {

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
            return "❌ Формат: `/rft-history [dd.MM.yyyy dd.MM.yyyy [персентиль]]`\nПример: `/rft-history 01.01.2025 31.03.2025 70`";
        }

        Map<String, Optional<StatusHistoryStat>> stats = this.issueService.getHistoryReport(from, to, percentile);

        return this.messageBuilder.buildHistoryReport(stats, from, to, percentile);
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
