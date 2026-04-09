package ru.entera.rftmonitor.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Jira issue that has been stuck in its current status longer than the allowed threshold.
 */
@Getter
@Builder
public final class StaleIssue {

    //region Fields

    private final String key;
    private final String summary;
    private final String assignee;
    private final Double storyPoints;
    private final String status;
    private final int daysSinceChange;
    private final String url;

    //endregion
}
