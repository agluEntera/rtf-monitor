package ru.entera.rftmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.entera.rftmonitor.client.JiraApiClient;
import ru.entera.rftmonitor.client.MySqlRepository;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests для {@link IssueService#getIssues()}.
 * <p>
 * Проверяет приоритет источников даты {@code enteredAt}:
 * <ol>
 *   <li>MySQL (нормальный случай — зеркало актуально)</li>
 *   <li>Jira changelog API — если MySQL не содержит запись (переход после ночной синхронизации)</li>
 *   <li>Дата создания задачи — последний резерв</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssueServiceGetIssuesTest {

    @Mock
    private AppConfig config;

    @Mock
    private JiraApiClient jiraApiClient;

    @Mock
    private MySqlRepository mySqlRepository;

    private IssueService issueService;

    @BeforeEach
    void setUp() {

        when(config.getThresholdBusinessDays()).thenReturn(4);
        issueService = new IssueService(config, jiraApiClient, mySqlRepository);
    }

    // region приоритет enteredAt

    @Test
    void enteredAt_mysqlUsed_whenPresent() {

        LocalDateTime mysqlDate = LocalDateTime.now().minusDays(10);
        when(jiraApiClient.fetchIssues()).thenReturn(List.of(
            rawIssue("EN-1", "Ready for Testing", LocalDateTime.now().minusDays(42))
        ));
        when(mySqlRepository.getEnteredDates(anyList()))
            .thenReturn(Map.of("EN-1_Ready for Testing", mysqlDate));

        List<Issue> issues = issueService.getIssues();

        assertEquals(mysqlDate, issues.get(0).getEnteredAt());
        verify(jiraApiClient, never()).fetchLastEnteredAt(anyString(), anyString());
    }

    @Test
    void enteredAt_jiraApiUsed_whenMysqlMissing() {

        // Сценарий EN-4717: задача переведена после ночной синхронизации MySQL
        LocalDateTime jiraDate = LocalDateTime.now().minusDays(1);
        when(jiraApiClient.fetchIssues()).thenReturn(List.of(
            rawIssue("EN-1", "Under Review", LocalDateTime.now().minusDays(42))
        ));
        when(mySqlRepository.getEnteredDates(anyList())).thenReturn(Map.of());
        when(jiraApiClient.fetchLastEnteredAt("EN-1", "Under Review"))
            .thenReturn(Optional.of(jiraDate));

        List<Issue> issues = issueService.getIssues();

        assertEquals(jiraDate, issues.get(0).getEnteredAt());
        assertFalse(issues.get(0).isOverdue()); // 1 раб. день <= порог 4
    }

    @Test
    void enteredAt_createdDateUsed_whenBothMysqlAndJiraEmpty() {

        LocalDateTime created = LocalDateTime.now().minusDays(3);
        when(jiraApiClient.fetchIssues()).thenReturn(List.of(
            rawIssue("EN-1", "Ready for Testing", created)
        ));
        when(mySqlRepository.getEnteredDates(anyList())).thenReturn(Map.of());
        when(jiraApiClient.fetchLastEnteredAt(eq("EN-1"), anyString()))
            .thenReturn(Optional.empty());

        List<Issue> issues = issueService.getIssues();

        assertEquals(created, issues.get(0).getEnteredAt());
    }

    @Test
    void enteredAt_null_whenCreatedDateAlsoNull() {

        when(jiraApiClient.fetchIssues()).thenReturn(List.of(
            rawIssue("EN-1", "Ready for Testing", null)
        ));
        when(mySqlRepository.getEnteredDates(anyList())).thenReturn(Map.of());
        when(jiraApiClient.fetchLastEnteredAt(anyString(), anyString()))
            .thenReturn(Optional.empty());

        List<Issue> issues = issueService.getIssues();

        assertNull(issues.get(0).getEnteredAt());
        assertFalse(issues.get(0).isOverdue());
    }

    // endregion

    // region просрочка

    @Test
    void overdue_true_whenBusinessDaysExceedThreshold() {

        // 10 дней назад → ~7 раб. дней > порог 4
        LocalDateTime date = LocalDateTime.now().minusDays(10);
        when(jiraApiClient.fetchIssues()).thenReturn(List.of(
            rawIssue("EN-1", "Ready for Testing", date)
        ));
        when(mySqlRepository.getEnteredDates(anyList()))
            .thenReturn(Map.of("EN-1_Ready for Testing", date));

        List<Issue> issues = issueService.getIssues();

        assertTrue(issues.get(0).isOverdue());
    }

    @Test
    void overdue_false_whenWithinThreshold() {

        // 1 день назад → 1 раб. день <= порог 4
        LocalDateTime date = LocalDateTime.now().minusDays(1);
        when(jiraApiClient.fetchIssues()).thenReturn(List.of(
            rawIssue("EN-1", "Ready for Testing", date)
        ));
        when(mySqlRepository.getEnteredDates(anyList()))
            .thenReturn(Map.of("EN-1_Ready for Testing", date));

        List<Issue> issues = issueService.getIssues();

        assertFalse(issues.get(0).isOverdue());
    }

    // endregion

    // region helpers

    private JiraApiClient.RawIssue rawIssue(String key, String status, LocalDateTime created) {

        return new JiraApiClient.RawIssue(
            key, "Summary", "User", 3.0, "Story", status, created, "http://url/" + key
        );
    }

    // endregion
}
