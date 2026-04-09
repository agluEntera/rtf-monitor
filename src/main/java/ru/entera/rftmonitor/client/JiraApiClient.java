package ru.entera.rftmonitor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.entera.rftmonitor.config.AppConfig;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for Jira REST API v3.
 * Fetches issues in monitored statuses.
 */
public class JiraApiClient {

    //region Constants

    private static final String SEARCH_PATH = "/rest/api/3/search/jql";
    private static final String CHANGELOG_PATH = "/rest/api/3/issue/%s/changelog";
    private static final String STORY_POINTS_FIELD = "customfield_10022";
    private static final DateTimeFormatter JIRA_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Maps Russian Jira status names (as returned by the REST API) to canonical English names
     * used throughout the application logic.
     */
    private static final Map<String, String> STATUS_NAME_ALIASES = Map.of(
        "Готово к проверке", "Ready for Review",
        "В работе",          "In Progress",
        "Закрыто",           "Closed"
    );

    //endregion

    //region Fields

    private final AppConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String authHeader;

    //endregion

    //region Ctor

    /**
     * @param config application configuration
     */
    public JiraApiClient(AppConfig config) {

        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
            (config.getJiraEmail() + ":" + config.getJiraApiToken()).getBytes()
        );

        HttpClient.Builder builder = HttpClient.newBuilder();

        if (config.hasProxy()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(config.getProxyHost(), config.getProxyPort())));
        }

        this.httpClient = builder.build();
    }

    //endregion

    //region Public

    /**
     * Fetches all issues in monitored RFT statuses from Jira.
     * Changelog (entered-at date) is not fetched here — use {@link ru.entera.rftmonitor.client.MySqlRepository}.
     *
     * @return list of raw issue data
     * @throws RuntimeException if the API call fails
     */
    public List<RawIssue> fetchIssues() {

        List<String> statuses = AppConfig.MONITORED_STATUSES;
        String jqlStatuses = statuses.stream().map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(", "));

        return this.fetchByJql("project = " + this.config.getJiraProject()
            + " AND sprint in openSprints()"
            + " AND status in (" + jqlStatuses + ") ORDER BY created ASC");
    }

    /**
     * Fetches all active (non-terminal) issues from Jira for staleness analysis.
     * Terminal statuses (Done, Closed) are excluded.
     *
     * @return list of raw issue data
     * @throws RuntimeException if the API call fails
     */
    public List<RawIssue> fetchAllActiveIssues() {

        List<String> terminal = AppConfig.TERMINAL_STATUSES;
        String excluded = terminal.stream().map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(", "));

        return this.fetchByJql("project = " + this.config.getJiraProject()
            + " AND sprint in openSprints()"
            + " AND status not in (" + excluded + ") ORDER BY updated ASC");
    }

    /**
     * Возвращает дату последнего перехода задачи в указанный статус через Jira API.
     * <p>
     * Используется как фолбэк, когда MySQL-зеркало ещё не обновилось после перехода
     * (обычно 0–несколько задач за запрос).
     *
     * @param issueKey     ключ задачи, например {@code EN-4717}
     * @param targetStatus целевой статус (канонический, английский)
     * @return дата последнего входа в статус, или {@code empty} если не найдено
     */
    public Optional<LocalDateTime> fetchLastEnteredAt(String issueKey, String targetStatus) {

        try {
            String url = this.config.getJiraUrl()
                + String.format(CHANGELOG_PATH, issueKey)
                + "?maxResults=100";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", this.authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode values = this.objectMapper.readTree(response.body()).path("values");
            LocalDateTime found = null;

            for (JsonNode history : values) {
                for (JsonNode item : history.path("items")) {
                    if ("status".equals(item.path("field").asText())) {
                        String toRaw = item.path("toString").asText();
                        String toStatus = STATUS_NAME_ALIASES.getOrDefault(toRaw, toRaw);

                        if (targetStatus.equals(toStatus)) {
                            String ts = history.path("created").asText("");
                            if (ts.length() >= 19) {
                                LocalDateTime date = LocalDateTime.parse(ts.substring(0, 19), JIRA_DATE_FORMAT);
                                if (found == null || date.isAfter(found)) {
                                    found = date;
                                }
                            }
                        }
                    }
                }
            }

            return Optional.ofNullable(found);
        } catch (Exception e) {
            System.err.println("[Jira] fetchLastEnteredAt error for " + issueKey + ": " + e.getMessage());

            return Optional.empty();
        }
    }

    //endregion

    //region Private

    private List<RawIssue> fetchByJql(String jql) {

        try {
            List<RawIssue> all = new ArrayList<>();
            String nextPageToken = null;

            do {
                ObjectNode body = this.objectMapper.createObjectNode();
                body.put("jql", jql);
                body.put("maxResults", 100);

                if (nextPageToken != null) {
                    body.put("nextPageToken", nextPageToken);
                }

                ArrayNode fields = this.objectMapper.createArrayNode();
                fields.add("summary");
                fields.add("status");
                fields.add("issuetype");
                fields.add("assignee");
                fields.add(STORY_POINTS_FIELD);
                fields.add("created");
                body.set("fields", fields);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.config.getJiraUrl() + SEARCH_PATH))
                    .header("Authorization", this.authHeader)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Jira API error: " + response.statusCode() + " — " + response.body());
                }

                JsonNode root = this.objectMapper.readTree(response.body());
                all.addAll(this.parseIssues(root));

                JsonNode tokenNode = root.path("nextPageToken");
                nextPageToken = tokenNode.isMissingNode() || tokenNode.isNull() ? null : tokenNode.asText();

            } while (nextPageToken != null);

            return all;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Jira issues: " + e.getMessage(), e);
        }
    }

    private List<RawIssue> parseIssues(JsonNode root) {

        List<RawIssue> result = new ArrayList<>();

        for (JsonNode issueNode : root.path("issues")) {
            String key = issueNode.path("key").asText();
            JsonNode fields = issueNode.path("fields");

            String summary = fields.path("summary").asText("");
            String statusRaw = fields.path("status").path("name").asText("");
            String status = STATUS_NAME_ALIASES.getOrDefault(statusRaw, statusRaw);
            String type = fields.path("issuetype").path("name").asText("");

            JsonNode assigneeNode = fields.path("assignee");
            String assignee = assigneeNode.isMissingNode() || assigneeNode.isNull()
                ? "—"
                : assigneeNode.path("displayName").asText("—");

            JsonNode spNode = fields.path(STORY_POINTS_FIELD);
            Double storyPoints = spNode.isMissingNode() || spNode.isNull() ? null : spNode.asDouble();

            String createdRaw = fields.path("created").asText("");
            LocalDateTime created = createdRaw.length() >= 19
                ? LocalDateTime.parse(createdRaw.substring(0, 19), JIRA_DATE_FORMAT)
                : null;

            String jiraUrl = this.config.getJiraUrl() + "/browse/" + key;

            result.add(new RawIssue(key, summary, assignee, storyPoints, type, status, created, jiraUrl));
        }

        return result;
    }

    //endregion

    //region Public static

    /**
     * Raw issue data fetched from Jira before enrichment with MySQL changelog data.
     *
     * @param key         Jira issue key (e.g. EN-4882)
     * @param summary     issue summary
     * @param assignee    display name of assignee, or "—" if unassigned
     * @param storyPoints story points value, or {@code null} if not set
     * @param type        issue type name
     * @param status      current status name
     * @param created     issue creation date (used as fallback if changelog is unavailable)
     * @param url         full Jira URL
     */
    public record RawIssue(
        String key,
        String summary,
        String assignee,
        Double storyPoints,
        String type,
        String status,
        LocalDateTime created,
        String url
    ) {}

    //endregion
}
