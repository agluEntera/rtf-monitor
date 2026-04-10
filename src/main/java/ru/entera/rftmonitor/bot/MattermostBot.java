package ru.entera.rftmonitor.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import ru.entera.rftmonitor.model.StaleReport;
import ru.entera.rftmonitor.service.IssueService;
import ru.entera.rftmonitor.service.MattermostMessageBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
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
        String responseJson = this.route(command);

        this.sendResponse(exchange, 200, responseJson);
    }

    private String route(String command) {

        try {
            boolean isStale = "/rft-stale".equals(command);
            List<Issue> issues = isStale ? List.of() : this.issueService.getIssues();
            Map<String, OptionalDouble> p70ByStatus = isStale ? Map.of() : this.buildP70Map();

            String text = switch (command) {
                case "/rft-status"  -> this.messageBuilder.buildFullReport(issues, p70ByStatus);
                case "/rft-review"  -> this.messageBuilder.buildGroupReport(issues, p70ByStatus, AppConfig.REVIEW_STATUSES);
                case "/rft-testing" -> this.messageBuilder.buildGroupReport(issues, p70ByStatus, AppConfig.TESTING_STATUSES);
                case "/rft-stale"   -> {
                    StaleReport staleReport = this.issueService.getStaleReport();
                    yield this.messageBuilder.buildStaleReport(staleReport);
                }
                default -> "Неизвестная команда. Доступны: /rft-status, /rft-review, /rft-testing, /rft-stale";
            };

            return this.responseJson(RESPONSE_TYPE_IN_CHANNEL, text);
        } catch (Exception e) {
            return this.responseJson(RESPONSE_TYPE_EPHEMERAL, "❌ Ошибка: " + e.getMessage());
        }
    }

    private Map<String, OptionalDouble> buildP70Map() {

        Map<String, OptionalDouble> p70ByStatus = new HashMap<>();

        for (String status : AppConfig.MONITORED_STATUSES) {
            p70ByStatus.put(status, this.issueService.getP70(status));
        }

        return p70ByStatus;
    }

    private boolean isTokenValid(String token) {

        java.util.Set<String> tokens = this.config.getMattermostTokens();

        if (tokens.isEmpty()) {
            System.err.println("[Mattermost] MATTERMOST_TOKEN не настроен — все запросы отклонены");
            return false;
        }

        return tokens.contains(token);
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
