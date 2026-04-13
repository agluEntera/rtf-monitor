package ru.entera.rftmonitor.model;

/**
 * Historical percentile statistics for a single Jira status.
 *
 * @param percentileValue computed percentile in SP·business-days
 * @param count           number of completed status transitions used in the calculation
 */
public record StatusHistoryStat(double percentileValue, int count) {}
