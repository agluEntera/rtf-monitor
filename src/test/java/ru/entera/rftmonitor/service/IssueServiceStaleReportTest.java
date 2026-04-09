package ru.entera.rftmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.entera.rftmonitor.client.JiraApiClient;
import ru.entera.rftmonitor.client.MySqlRepository;
import ru.entera.rftmonitor.model.StaleReport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IssueService#getStaleReport()}.
 * <p>
 * Jira and MySQL dependencies are mocked.
 * Dates are chosen to be unambiguous relative to business-day thresholds:
 * <ul>
 *   <li>{@code freshDate()} — today, 0 business days → below every threshold</li>
 *   <li>{@code staleDate()} — 10 calendar days ago, ≥ 6 business days → above inProgress(2) and queue(4) thresholds</li>
 *   <li>{@code veryStaleDate()} — 60 calendar days ago, ≥ 42 business days → above abandoned(8) threshold</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IssueServiceStaleReportTest {

    @Mock
    private JiraApiClient jiraApiClient;

    @Mock
    private MySqlRepository mySqlRepository;

    private IssueService issueService;

    @BeforeEach
    void setUp() {

        issueService = new IssueService(null, jiraApiClient, mySqlRepository);
    }

    //region inProgress category

    @Test
    void inProgress_whenStatusIsInProgressAndStale_isIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "In Progress")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", staleDate()));

        StaleReport report = issueService.getStaleReport();

        assertEquals(1, report.inProgress().size());
        assertEquals("EN-1", report.inProgress().get(0).getKey());
    }

    @Test
    void inProgress_whenStatusIsInProgressAndFresh_isNotIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "In Progress")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", freshDate()));

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.inProgress().isEmpty());
    }

    @Test
    void inProgress_whenStatusIsReadyForTesting_isNotIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "Ready for Testing")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", staleDate()));

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.inProgress().isEmpty());
    }

    //endregion

    //region longQueue category

    @Test
    void longQueue_whenStatusIsReadyForTestingAndStale_isIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "Ready for Testing")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", staleDate()));

        StaleReport report = issueService.getStaleReport();

        assertEquals(1, report.longQueue().size());
        assertEquals("EN-1", report.longQueue().get(0).getKey());
    }

    @Test
    void longQueue_whenStatusIsReadyForReviewAndStale_isIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "Ready for Review")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", staleDate()));

        StaleReport report = issueService.getStaleReport();

        assertEquals(1, report.longQueue().size());
    }

    @Test
    void longQueue_whenStatusIsInProgress_isNotIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "In Progress")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", staleDate()));

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.longQueue().isEmpty());
    }

    //endregion

    //region abandoned category

    @Test
    void abandoned_whenNonTerminalStatusAndVeryStale_isIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "In Progress")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", veryStaleDate()));

        StaleReport report = issueService.getStaleReport();

        assertEquals(1, report.abandoned().size());
    }

    @Test
    void abandoned_whenTerminalStatusAndVeryStale_isNotIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "Done")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", veryStaleDate()));

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.abandoned().isEmpty());
    }

    @Test
    void abandoned_whenClosedStatusAndVeryStale_isNotIncluded() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "Closed")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", veryStaleDate()));

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.abandoned().isEmpty());
    }

    @Test
    void inProgressAndAbandoned_whenInTestingAndVeryStale_isInBothCategories() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(rawIssue("EN-1", "In Testing")));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of("EN-1", veryStaleDate()));

        StaleReport report = issueService.getStaleReport();

        assertEquals(1, report.inProgress().size());
        assertEquals(1, report.abandoned().size());
        assertEquals("EN-1", report.inProgress().get(0).getKey());
        assertEquals("EN-1", report.abandoned().get(0).getKey());
    }

    //endregion

    //region fallback and edge cases

    @Test
    void fallbackToCreatedDate_whenNoMysqlEntry() {

        JiraApiClient.RawIssue issue = new JiraApiClient.RawIssue(
            "EN-1", "Summary", "User", null, "Story", "In Progress", staleDate(), "http://url"
        );
        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(issue));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of());

        StaleReport report = issueService.getStaleReport();

        assertEquals(1, report.inProgress().size());
    }

    @Test
    void skipsIssue_whenNoMysqlEntryAndNoCreatedDate() {

        JiraApiClient.RawIssue issue = new JiraApiClient.RawIssue(
            "EN-1", "Summary", "User", null, "Story", "In Progress", null, "http://url"
        );
        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(issue));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of());

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.inProgress().isEmpty());
    }

    @Test
    void emptyJiraResponse_returnsEmptyReport() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of());
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of());

        StaleReport report = issueService.getStaleReport();

        assertTrue(report.inProgress().isEmpty());
        assertTrue(report.longQueue().isEmpty());
        assertTrue(report.abandoned().isEmpty());
    }

    //endregion

    //region sorting

    @Test
    void inProgress_sortedByDaysSinceChangeDescending() {

        when(jiraApiClient.fetchAllActiveIssues()).thenReturn(List.of(
            rawIssue("EN-1", "In Progress"),
            rawIssue("EN-2", "In Progress")
        ));
        when(mySqlRepository.getLastStatusChangeDates(anyList())).thenReturn(Map.of(
            "EN-1", LocalDateTime.now().minusDays(10),
            "EN-2", LocalDateTime.now().minusDays(30)
        ));

        StaleReport report = issueService.getStaleReport();

        assertEquals(2, report.inProgress().size());
        assertTrue(
            report.inProgress().get(0).getDaysSinceChange() >= report.inProgress().get(1).getDaysSinceChange()
        );
    }

    //endregion

    //region Private helpers

    private JiraApiClient.RawIssue rawIssue(String key, String status) {

        return new JiraApiClient.RawIssue(
            key, "Summary", "User", 3.0, "Story", status, LocalDateTime.now(), "http://url/" + key
        );
    }

    /** 0 business days — below every threshold. */
    private LocalDateTime freshDate() {

        return LocalDateTime.now();
    }

    /** ≥ 6 business days — above inProgress(2) and queue(4) thresholds. */
    private LocalDateTime staleDate() {

        return LocalDateTime.now().minusDays(10);
    }

    /** ≥ 42 business days — above abandoned(8) threshold. */
    private LocalDateTime veryStaleDate() {

        return LocalDateTime.now().minusDays(60);
    }

    //endregion
}
