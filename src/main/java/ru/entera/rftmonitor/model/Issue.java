package ru.entera.rftmonitor.model;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/**
 * Immutable snapshot of a Jira issue being monitored.
 */
@Getter
@Builder
public final class Issue {

    //region Fields

    private final String key;
    private final String summary;
    private final String assignee;
    private final Double storyPoints;
    private final String type;
    private final String status;
    private final LocalDateTime enteredAt;
    private final Integer businessDays;
    private final boolean overdue;
    private final String url;

    //endregion
}
