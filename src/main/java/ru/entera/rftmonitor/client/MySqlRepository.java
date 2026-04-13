package ru.entera.rftmonitor.client;

import ru.entera.rftmonitor.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Repository for reading Jira data from the MySQL mirror database.
 */
public class MySqlRepository {

    //region Fields

    private final AppConfig config;
    private final String jdbcUrl;

    //endregion

    //region Ctor

    /**
     * @param config application configuration with MySQL credentials
     */
    public MySqlRepository(AppConfig config) {

        this.config = config;
        this.jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000",
            config.getMysqlHost(),
            config.getMysqlPort(),
            config.getMysqlDb()
        );
    }

    //endregion

    //region Public

    /**
     * Returns the last transition date into a monitored status for each issue key.
     * <p>
     * Key format in the returned map: {@code "issueKey_statusName"}, e.g. {@code "EN-4882_Ready for Testing"}.
     *
     * @param issueKeys list of Jira issue keys to look up
     * @return map from "key_status" to the last datetime the issue entered that status
     */
    public Map<String, LocalDateTime> getEnteredDates(List<String> issueKeys) {

        Map<String, LocalDateTime> result = new HashMap<>();

        if (issueKeys.isEmpty()) {

            return result;
        }

        List<String> statuses = AppConfig.MONITORED_STATUSES;
        String statusPlaceholders = this.buildPlaceholders(statuses.size());
        String keyPlaceholders = this.buildPlaceholders(issueKeys.size());

        String sql = """
            SELECT i.IssueKey, c.New, MAX(c.CreatedDate) AS entered_at
            FROM DetailedIssuesChangelog c
            JOIN IssuesInfo i ON i.IssueId = c.IssueId
            WHERE c.Field = 'status'
              AND c.New IN (%s)
              AND i.IssueKey IN (%s)
            GROUP BY i.IssueKey, c.New
            """.formatted(statusPlaceholders, keyPlaceholders);

        try (Connection conn = this.connect();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int idx = 1;

            for (String status : statuses) {
                stmt.setString(idx++, status);
            }

            for (String key : issueKeys) {
                stmt.setString(idx++, key);
            }

            try (ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String issueKey = rs.getString("IssueKey");
                    String status = rs.getString("New");
                    LocalDateTime enteredAt = rs.getObject("entered_at", LocalDateTime.class);

                    result.put(issueKey + "_" + status, enteredAt);
                }
            }
        } catch (SQLException e) {
            System.err.println("[MySQL] getEnteredDates error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Computes the N-th percentile of volume-weighted time historical issues spent in the given status.
     * <p>
     * Data source: {@code DetailedIssuesChangelog} — each enter/exit pair for the status is one
     * data point. Only pairs whose <b>exit date</b> falls within [{@code fromDate}, {@code toDate}]
     * are included, and only issues with SP > 0.
     * <p>
     * Metric per data point: {@code businessDays × storyPoints}, where
     * {@code businessDays = hours / 24 × (5/7)}.
     * A 4-SP task that waited 1 day contributes 4; a 1-SP task contributes 1.
     *
     * @param status     Jira status name (e.g. "Ready for Testing")
     * @param fromDate   start of the exit-date filter range (inclusive)
     * @param toDate     end of the exit-date filter range (inclusive)
     * @param percentile percentile to compute (e.g. 50, 70, 95)
     * @return percentile value in SP·business-days, or empty if no historical data
     */
    public OptionalDouble getPercentile(String status, LocalDate fromDate, LocalDate toDate, int percentile) {

        // For each transition INTO `status` (New = status), find the first transition
        // OUT OF `status` (Old = status) that follows it. Filter to pairs where the
        // exit falls within [fromDate, toDate].
        String sql = """
            SELECT
                TIMESTAMPDIFF(HOUR, entries.enter_date, entries.exit_date) AS hours_in_status,
                i.SP
            FROM (
                SELECT
                    e.IssueId,
                    e.CreatedDate AS enter_date,
                    (SELECT MIN(x.CreatedDate)
                     FROM DetailedIssuesChangelog x
                     WHERE x.IssueId = e.IssueId
                       AND x.Field = 'status'
                       AND x.Old = ?
                       AND x.CreatedDate > e.CreatedDate) AS exit_date
                FROM DetailedIssuesChangelog e
                WHERE e.Field = 'status'
                  AND e.New = ?
            ) AS entries
            JOIN IssuesInfo i ON i.IssueId = entries.IssueId
            WHERE entries.exit_date IS NOT NULL
              AND DATE(entries.exit_date) BETWEEN ? AND ?
              AND COALESCE(i.SP, 0) > 0
              AND i.IssueKey LIKE ?
              AND TIMESTAMPDIFF(HOUR, entries.enter_date, entries.exit_date) > 0
            """;

        List<Double> values = new ArrayList<>();

        try (Connection conn = this.connect();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, status);
            stmt.setString(2, status);
            stmt.setObject(3, fromDate);
            stmt.setObject(4, toDate);
            stmt.setString(5, this.config.getJiraProject() + "-%");

            try (ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    double hours = rs.getDouble("hours_in_status");
                    double sp = rs.getDouble("SP");
                    double businessDays = hours / 24.0 * (5.0 / 7.0);
                    values.add(businessDays * sp);
                }
            }
        } catch (SQLException e) {
            System.err.println("[MySQL] getPercentile error for '" + status + "': " + e.getMessage());

            return OptionalDouble.empty();
        }

        if (values.isEmpty()) {

            return OptionalDouble.empty();
        }

        return OptionalDouble.of(this.percentile(values, percentile));
    }

    /**
     * Returns the last status-change date for each of the given issue keys.
     * Uses {@code DetailedIssuesChangelog} — updated once per day.
     *
     * @param issueKeys list of Jira issue keys to look up
     * @return map from issue key to the last datetime any status change occurred
     */
    public Map<String, LocalDateTime> getLastStatusChangeDates(List<String> issueKeys) {

        Map<String, LocalDateTime> result = new HashMap<>();

        if (issueKeys.isEmpty()) {

            return result;
        }

        String keyPlaceholders = this.buildPlaceholders(issueKeys.size());

        String sql = """
            SELECT i.IssueKey, MAX(c.CreatedDate) AS last_change
            FROM DetailedIssuesChangelog c
            JOIN IssuesInfo i ON i.IssueId = c.IssueId
            WHERE c.Field = 'status'
              AND i.IssueKey IN (%s)
            GROUP BY i.IssueKey
            """.formatted(keyPlaceholders);

        try (Connection conn = this.connect();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int idx = 1;

            for (String key : issueKeys) {
                stmt.setString(idx++, key);
            }

            try (ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    result.put(
                        rs.getString("IssueKey"),
                        rs.getObject("last_change", LocalDateTime.class)
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[MySQL] getLastStatusChangeDates error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Returns all issues from {@code IssuesInfo} with their last known status and developer.
     * Useful for developer-level statistics across all statuses.
     *
     * @return list of raw issue rows
     */
    public List<IssueRow> getAllIssues() {

        String sql = """
            SELECT
                i.IssueKey,
                i.Summary,
                i.TypeName,
                i.SP,
                i.Status,
                i.Developer,
                i.ReleaseDate,
                i.Sprint,
                i.SprintCount
            FROM IssuesInfo i
            """;

        List<IssueRow> result = new ArrayList<>();

        try (Connection conn = this.connect();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                result.add(new IssueRow(
                    rs.getString("IssueKey"),
                    rs.getString("Summary"),
                    rs.getString("TypeName"),
                    rs.getObject("SP") != null ? rs.getDouble("SP") : null,
                    rs.getString("Status"),
                    rs.getString("Developer"),
                    rs.getString("ReleaseDate"),
                    rs.getString("Sprint"),
                    rs.getObject("SprintCount") != null ? rs.getInt("SprintCount") : null
                ));
            }
        } catch (SQLException e) {
            System.err.println("[MySQL] getAllIssues error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Returns status duration history for all issues.
     * Each row contains hours spent in each monitored status.
     *
     * @return list of duration rows
     */
    public List<StatusDurationRow> getStatusDurations() {

        String sql = """
            SELECT
                s.Key                          AS issue_key,
                s.current_status,
                `Ready for Testing`            AS rft_hours,
                `Ready for Review`             AS rfr_hours,
                `In Testing`                   AS it_hours
            FROM IssueStatusDurations s
            WHERE `Ready for Testing` > 0
               OR `Ready for Review` > 0
               OR `In Testing` > 0
            """;

        List<StatusDurationRow> result = new ArrayList<>();

        try (Connection conn = this.connect();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                result.add(new StatusDurationRow(
                    rs.getString("issue_key"),
                    rs.getString("current_status"),
                    rs.getObject("rft_hours") != null ? rs.getDouble("rft_hours") : null,
                    rs.getObject("rfr_hours") != null ? rs.getDouble("rfr_hours") : null,
                    rs.getObject("it_hours") != null ? rs.getDouble("it_hours") : null
                ));
            }
        } catch (SQLException e) {
            System.err.println("[MySQL] getStatusDurations error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Returns changelog entries for a specific issue.
     *
     * @param issueKey Jira issue key (e.g. EN-4882)
     * @return list of changelog rows ordered by date ascending
     */
    public List<ChangelogRow> getChangelog(String issueKey) {

        String sql = """
            SELECT c.Field, c.Old, c.New, c.CreatedDate
            FROM DetailedIssuesChangelog c
            JOIN IssuesInfo i ON i.IssueId = c.IssueId
            WHERE i.IssueKey = ?
            ORDER BY c.CreatedDate ASC
            """;

        List<ChangelogRow> result = new ArrayList<>();

        try (Connection conn = this.connect();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, issueKey);

            try (ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    result.add(new ChangelogRow(
                        rs.getString("Field"),
                        rs.getString("Old"),
                        rs.getString("New"),
                        rs.getObject("CreatedDate", LocalDateTime.class)
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MySQL] getChangelog error for '" + issueKey + "': " + e.getMessage());
        }

        return result;
    }

    //endregion

    //region Public static

    /**
     * Raw row from {@code IssuesInfo}.
     */
    public record IssueRow(
        String issueKey,
        String summary,
        String typeName,
        Double sp,
        String status,
        String developer,
        String releaseDate,
        String sprint,
        Integer sprintCount
    ) {}

    /**
     * Raw row from {@code IssueStatusDurations} with hours spent in each monitored status.
     */
    public record StatusDurationRow(
        String issueKey,
        String currentStatus,
        Double readyForTestingHours,
        Double readyForReviewHours,
        Double inTestingHours
    ) {}

    /**
     * Raw row from {@code DetailedIssuesChangelog}.
     */
    public record ChangelogRow(
        String field,
        String oldValue,
        String newValue,
        LocalDateTime createdDate
    ) {}

    //endregion

    //region Private

    private Connection connect() throws SQLException {

        return DriverManager.getConnection(this.jdbcUrl, this.config.getMysqlUser(), this.config.getMysqlPassword());
    }

    private String buildPlaceholders(int count) {

        return "?,".repeat(count - 1) + "?";
    }

    private double percentile(List<Double> values, int p) {

        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);

        double index = (p / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {

            return Math.round(sorted.get(lower) * 10.0) / 10.0;
        }

        double interpolated = sorted.get(lower) * (1.0 - (index - lower))
            + sorted.get(upper) * (index - lower);

        return Math.round(interpolated * 10.0) / 10.0;
    }

    //endregion
}
