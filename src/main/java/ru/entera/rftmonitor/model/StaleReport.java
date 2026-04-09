package ru.entera.rftmonitor.model;

import java.util.List;

/**
 * Report of issues stuck in their current status beyond defined thresholds.
 *
 * @param inProgress issues in active-work statuses stuck more than {@code STALE_IN_PROGRESS_DAYS}
 * @param longQueue  issues in queue statuses stuck more than {@code STALE_QUEUE_DAYS}
 * @param abandoned  issues in any non-terminal status stuck more than {@code STALE_ABANDONED_DAYS}
 */
public record StaleReport(
    List<StaleIssue> inProgress,
    List<StaleIssue> longQueue,
    List<StaleIssue> abandoned
) {}
