package ru.entera.rftmonitor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.entera.rftmonitor.config.AppConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

/**
 * Client for Mattermost REST API v4.
 * Registers slash commands on bot startup and returns their verification tokens.
 */
public class MattermostApiClient {

    //region Fields

    private final AppConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    //endregion

    //region Ctor

    public MattermostApiClient(AppConfig config) {

        this.config = config;
        this.httpClient = HttpClient.newBuilder().build();
        this.objectMapper = new ObjectMapper();
    }

    //endregion

    //region Public

    /**
     * Registers all slash commands in Mattermost.
     * Existing commands created by this bot are deleted and recreated.
     *
     * @return set of verification tokens issued by Mattermost for incoming slash command requests
     */
    public Set<String> registerCommands() {

        try {
            String baseUrl = this.config.getMattermostUrl();
            String token = this.config.getMattermostToken();

            String myUserId = this.objectMapper
                .readTree(this.get(baseUrl + "/api/v4/users/me", token))
                .path("id").asText();

            String teamId = this.objectMapper
                .readTree(this.get(baseUrl + "/api/v4/teams/name/" + this.config.getMattermostTeam(), token))
                .path("id").asText();

            // Delete existing commands created by this bot
            JsonNode existing = this.objectMapper
                .readTree(this.get(baseUrl + "/api/v4/commands?team_id=" + teamId + "&custom_only=true", token));

            for (JsonNode cmd : existing) {
                if (AppConfig.SLASH_COMMAND_TRIGGERS.contains(cmd.path("trigger").asText())) {
                    this.delete(baseUrl + "/api/v4/commands/" + cmd.path("id").asText(), token);
                    System.out.println("[Mattermost] Removed old /" + cmd.path("trigger").asText());
                }
            }

            // Create commands and collect verification tokens
            Set<String> verificationTokens = new HashSet<>();
            for (AppConfig.SlashCommand cmd : AppConfig.SLASH_COMMANDS) {
                ObjectNode body = this.objectMapper.createObjectNode();
                body.put("team_id", teamId);
                body.put("method", "P");
                body.put("trigger", cmd.trigger());
                body.put("url", this.config.getMattermostBotUrl());
                body.put("username", "RFT Monitor");
                body.put("description", cmd.description());
                body.put("auto_complete", true);
                body.put("auto_complete_desc", cmd.description());

                String response = this.post(baseUrl + "/api/v4/commands", token, body.toString());
                String verToken = this.objectMapper.readTree(response).path("token").asText();
                if (!verToken.isEmpty()) {
                    verificationTokens.add(verToken);
                }
                System.out.println("[Mattermost] Registered /" + cmd.trigger());
            }

            return verificationTokens;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register Mattermost commands: " + e.getMessage(), e);
        }
    }

    //endregion

    //region Private

    private String get(String url, String token) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("GET " + url + " → " + response.statusCode() + ": " + response.body());
        }

        return response.body();
    }

    private void delete(String url, String token) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();

        this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String post(String url, String token, String body) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("POST " + url + " → " + response.statusCode() + ": " + response.body());
        }

        return response.body();
    }

    //endregion
}
