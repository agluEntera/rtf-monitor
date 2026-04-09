package ru.entera.rftmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.StaleIssue;
import ru.entera.rftmonitor.model.StaleReport;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessageBuilder#buildStaleReport(StaleReport)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageBuilderStaleReportTest {

    @Mock
    private AppConfig config;

    private MessageBuilder messageBuilder;

    @BeforeEach
    void setUp() {

        when(config.getJiraProject()).thenReturn("EN");
        messageBuilder = new MessageBuilder(config);
    }

    @Test
    void emptyReport_returnsNoStaleMessage() {

        StaleReport report = new StaleReport(List.of(), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertEquals("✅ Зависших задач не обнаружено", result);
    }

    @Test
    void inProgressSection_containsHeaderWithThresholdDays() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-1", "In Progress", 3)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("В работе"));
        assertTrue(result.contains(String.valueOf(AppConfig.STALE_IN_PROGRESS_DAYS)));
    }

    @Test
    void longQueueSection_containsHeaderWithThresholdDays() {

        StaleReport report = new StaleReport(List.of(), List.of(staleIssue("EN-1", "Ready for Testing", 6)), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("Долгая очередь"));
        assertTrue(result.contains(String.valueOf(AppConfig.STALE_QUEUE_DAYS)));
    }

    @Test
    void abandonedSection_containsHeaderWithThresholdDays() {

        StaleReport report = new StaleReport(List.of(), List.of(), List.of(staleIssue("EN-1", "In Progress", 10)));

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("Покинутые"));
        assertTrue(result.contains(String.valueOf(AppConfig.STALE_ABANDONED_DAYS)));
    }

    @Test
    void issueLine_containsKeyDaysAndStatus() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-42", "In Progress", 5)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("EN-42"));
        assertTrue(result.contains("5 дн."));
        assertTrue(result.contains("In Progress"));
    }

    @Test
    void issueLine_containsHtmlLink() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-42", "In Progress", 5)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("<a href=\"http://url/EN-42\">EN-42</a>"));
    }

    @Test
    void issueLine_containsStoryPoints_whenPresent() {

        StaleIssue issue = StaleIssue.builder()
            .key("EN-1").summary("S").assignee("User").status("In Progress")
            .daysSinceChange(3).storyPoints(5.0).url("http://url/EN-1")
            .build();
        StaleReport report = new StaleReport(List.of(issue), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("5.0 SP"));
    }

    @Test
    void conditionsNote_containsProjectAndSprintInfo() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-1", "In Progress", 3)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("EN"));
        assertTrue(result.contains("активный"));
    }

    @Test
    void onlyInProgressPresent_doesNotContainQueueOrAbandonedSectionEmoji() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-1", "In Progress", 3)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertFalse(result.contains("🕐"));
        assertFalse(result.contains("💀"));
    }

    //region Private helpers

    private StaleIssue staleIssue(String key, String status, int days) {

        return StaleIssue.builder()
            .key(key).summary("Summary").assignee("User").status(status)
            .daysSinceChange(days).url("http://url/" + key)
            .build();
    }

    //endregion
}
