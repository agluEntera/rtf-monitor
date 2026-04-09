package ru.entera.rftmonitor.service;

import ru.entera.rftmonitor.client.JiraApiClient;
import ru.entera.rftmonitor.client.MySqlRepository;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import ru.entera.rftmonitor.model.StaleIssue;
import ru.entera.rftmonitor.model.StaleReport;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Orchestrates data from Jira and MySQL to produce enriched {@link Issue} objects.
 */
public final class IssueService {

    //region Fields

    private final AppConfig config;
    private final JiraApiClient jiraApiClient;
    private final MySqlRepository mySqlRepository;

    //endregion

    //region Ctor

    /**
     * @param config          application configuration
     * @param jiraApiClient   Jira API client
     * @param mySqlRepository MySQL repository
     */
    public IssueService(AppConfig config, JiraApiClient jiraApiClient, MySqlRepository mySqlRepository) {

        this.config = config;
        this.jiraApiClient = jiraApiClient;
        this.mySqlRepository = mySqlRepository;
    }

    //endregion

    //region Public

    /**
     * Fetches all monitored issues enriched with entered-at dates from MySQL.
     *
     * @return list of issues with computed {@code businessDays} and {@code overdue} fields
     */
    public List<Issue> getIssues() {

        List<JiraApiClient.RawIssue> rawIssues = this.jiraApiClient.fetchIssues();
        List<String> keys = rawIssues.stream().map(JiraApiClient.RawIssue::key).toList();
        Map<String, LocalDateTime> enteredDates = this.mySqlRepository.getEnteredDates(keys);

        LocalDateTime now = LocalDateTime.now();
        List<Issue> result = new ArrayList<>();

        for (JiraApiClient.RawIssue raw : rawIssues) {
            LocalDateTime enteredAt = enteredDates.get(raw.key() + "_" + raw.status());

            if (enteredAt == null) {
                enteredAt = raw.created();
            }

            Integer bdays = enteredAt != null
                ? this.businessDays(enteredAt.toLocalDate(), now.toLocalDate())
                : null;

            boolean overdue = bdays != null && bdays > this.config.getThresholdBusinessDays();

            result.add(Issue.builder()
                .key(raw.key())
                .summary(raw.summary())
                .assignee(raw.assignee())
                .storyPoints(raw.storyPoints())
                .type(raw.type())
                .status(raw.status())
                .enteredAt(enteredAt)
                .businessDays(bdays)
                .overdue(overdue)
                .url(raw.url())
                .build());
        }

        return result;
    }

    /**
     * Fetches all active (non-terminal) issues and groups them into stale categories.
     * <p>
     * Categories are based on how long the status has not changed (in business days):
     * <ul>
     *   <li><b>inProgress</b> — In Progress / In Review / In Testing, stale &gt; {@code STALE_IN_PROGRESS_DAYS}</li>
     *   <li><b>longQueue</b> — Ready for Review / Ready for Testing, stale &gt; {@code STALE_QUEUE_DAYS}</li>
     *   <li><b>abandoned</b> — any non-terminal status, stale &gt; {@code STALE_ABANDONED_DAYS}</li>
     * </ul>
     *
     * @return stale report with three categorized lists, each sorted by days descending
     */
    public StaleReport getStaleReport() {

        List<JiraApiClient.RawIssue> rawIssues = this.jiraApiClient.fetchAllActiveIssues();
        List<String> keys = rawIssues.stream().map(JiraApiClient.RawIssue::key).toList();
        Map<String, LocalDateTime> lastChangeDates = this.mySqlRepository.getLastStatusChangeDates(keys);

        LocalDate today = LocalDate.now();
        List<StaleIssue> inProgress = new ArrayList<>();
        List<StaleIssue> longQueue = new ArrayList<>();
        List<StaleIssue> abandoned = new ArrayList<>();

        for (JiraApiClient.RawIssue raw : rawIssues) {
            LocalDateTime lastChange = lastChangeDates.get(raw.key());

            if (lastChange == null) {
                lastChange = raw.created();
            }

            if (lastChange == null) {
                continue;
            }

            int days = this.businessDays(lastChange.toLocalDate(), today);
            String status = raw.status();

            StaleIssue staleIssue = StaleIssue.builder()
                .key(raw.key())
                .summary(raw.summary())
                .assignee(raw.assignee())
                .storyPoints(raw.storyPoints())
                .status(status)
                .daysSinceChange(days)
                .url(raw.url())
                .build();

            if (AppConfig.STALE_IN_PROGRESS_STATUSES.contains(status) && days > AppConfig.STALE_IN_PROGRESS_DAYS) {
                inProgress.add(staleIssue);
            }

            if (AppConfig.STALE_QUEUE_STATUSES.contains(status) && days > AppConfig.STALE_QUEUE_DAYS) {
                longQueue.add(staleIssue);
            }

            if (!AppConfig.TERMINAL_STATUSES.contains(status) && days > AppConfig.STALE_ABANDONED_DAYS) {
                abandoned.add(staleIssue);
            }
        }

        Comparator<StaleIssue> byDaysDesc = Comparator.comparingInt(StaleIssue::getDaysSinceChange).reversed();
        inProgress.sort(byDaysDesc);
        longQueue.sort(byDaysDesc);
        abandoned.sort(byDaysDesc);

        return new StaleReport(inProgress, longQueue, abandoned);
    }

    /**
     * Returns the 70th percentile of time in the given status from historical closed issues.
     *
     * @param status status name
     * @return P70 in business days, or empty if no data
     */
    public OptionalDouble getP70(String status) {

        return this.mySqlRepository.getP70BusinessDays(status);
    }

    //endregion

    //region Private static

    private int businessDays(LocalDate start, LocalDate end) {

        int days = 0;
        LocalDate cur = start;

        while (cur.isBefore(end)) {
            if (cur.getDayOfWeek() != DayOfWeek.SATURDAY && cur.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }

            cur = cur.plusDays(1);
        }

        return days;
    }

    //endregion
}
