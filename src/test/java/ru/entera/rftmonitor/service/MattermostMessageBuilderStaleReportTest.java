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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MattermostMessageBuilder#buildStaleReport(StaleReport)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MattermostMessageBuilderStaleReportTest {

    @Mock
    private AppConfig config;

    private MattermostMessageBuilder messageBuilder;

    @BeforeEach
    void setUp() {

        when(config.getJiraProject()).thenReturn("EN");
        messageBuilder = new MattermostMessageBuilder(config);
    }

    @Test
    void emptyReport_returnsNoStaleMessage() {

        StaleReport report = new StaleReport(List.of(), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertEquals("✅ Зависших задач не обнаружено", result);
    }

    @Test
    void inProgressSection_usesMarkdownBoldHeader() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-1", "In Progress", 3)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("**В работе"));
    }

    @Test
    void issueLine_usesMarkdownLink() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-42", "In Progress", 5)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("[EN-42](http://url/EN-42)"));
    }

    @Test
    void issueLine_containsDaysAndStatus() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-42", "In Progress", 5)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("5 дн."));
        assertTrue(result.contains("In Progress"));
    }

    @Test
    void conditionsNote_usesMarkdownItalic() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-1", "In Progress", 3)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.contains("_📌"));
        assertTrue(result.endsWith("_"));
    }

    @Test
    void conditionsNote_doesNotContainHtmlTags() {

        StaleReport report = new StaleReport(List.of(staleIssue("EN-1", "In Progress", 3)), List.of(), List.of());

        String result = messageBuilder.buildStaleReport(report);

        assertTrue(result.indexOf('<') == -1 || result.contains("[EN-1]"));
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
