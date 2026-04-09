package ru.entera.rftmonitor.service;

import ru.entera.rftmonitor.config.AppConfig;
import ru.entera.rftmonitor.model.Issue;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Builds Markdown-formatted messages for Mattermost from issue data.
 * <p>
 * Mattermost uses standard Markdown: {@code **bold**}, {@code [text](url)}, etc.
 * Unlike {@link MessageBuilder} which produces Telegram HTML.
 */
public final class MattermostMessageBuilder {

    //region Constants

    private static final Map<String, String> STATUS_EMOJI = Map.of(
        "Ready for Testing", "🧪",
        "Ready for Review",  "👀",
        "In Testing",        "⚙️"
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    //endregion

    //region Fields

    private final AppConfig config;

    //endregion

    //region Ctor

    /**
     * @param config application configuration (threshold, target percent)
     */
    public MattermostMessageBuilder(AppConfig config) {

        this.config = config;
    }

    //endregion

    //region Public

    /**
     * Builds a full monitoring report grouped by status.
     *
     * @param issues       all monitored issues
     * @param p70ByStatus  map from status name to P70 in business days
     * @return Markdown-formatted message
     */
    public String buildFullReport(List<Issue> issues, Map<String, OptionalDouble> p70ByStatus) {

        String today = LocalDateTime.now().format(DATE_FORMAT);
        StringBuilder sb = new StringBuilder();
        sb.append("## 📋 Мониторинг тестирования — ").append(today).append("\n\n");

        for (String status : AppConfig.MONITORED_STATUSES) {
            List<Issue> group = issues.stream()
                .filter(i -> status.equals(i.getStatus()))
                .toList();

            String emoji = STATUS_EMOJI.getOrDefault(status, "📋");

            if (group.isEmpty()) {
                sb.append(emoji).append(" **").append(status).append("** — нет задач\n");
                continue;
            }

            OptionalDouble p70 = p70ByStatus.getOrDefault(status, OptionalDouble.empty());
            sb.append(this.buildStatusSection(status, emoji, group, p70));
            sb.append("\n");
        }

        int total = issues.size();
        long ok = issues.stream().filter(i -> !i.isOverdue()).count();
        int pct = total > 0 ? (int) Math.round((double) ok / total * 100) : 0;
        String flag = pct >= this.config.getTargetPercent() ? "✅" : "⚠️";

        sb.append(flag).append(" **Итого: on-time ")
            .append(ok).append("/").append(total)
            .append(" = ").append(pct).append("%**  (цель ≥ ")
            .append(this.config.getTargetPercent()).append("%)");

        return sb.toString();
    }

    /**
     * Builds a message listing only overdue issues grouped by status.
     *
     * @param issues all monitored issues
     * @return Markdown-formatted message
     */
    public String buildOverdueReport(List<Issue> issues) {

        List<Issue> overdue = issues.stream().filter(Issue::isOverdue).toList();

        if (overdue.isEmpty()) {

            return "✅ Просроченных задач нет (порог: " + this.config.getThresholdBusinessDays() + " раб. дней)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 🔴 Просроченные задачи (").append(overdue.size()).append(" шт.)\n\n");

        for (String status : AppConfig.MONITORED_STATUSES) {
            List<Issue> group = overdue.stream()
                .filter(i -> status.equals(i.getStatus()))
                .sorted(Comparator.comparingInt((Issue i) -> i.getBusinessDays() == null ? 0 : i.getBusinessDays()).reversed())
                .toList();

            if (group.isEmpty()) {
                continue;
            }

            String emoji = STATUS_EMOJI.getOrDefault(status, "📋");
            sb.append(emoji).append(" **").append(status).append("**\n");

            for (Issue issue : group) {
                sb.append(this.formatIssueLine(issue, true)).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Builds a statistics report grouped by assignee.
     *
     * @param issues       all monitored issues
     * @param p70ByStatus  map from status name to P70 in business days
     * @return Markdown-formatted message
     */
    public String buildStatsReport(List<Issue> issues, Map<String, OptionalDouble> p70ByStatus) {

        Map<String, int[]> devStats = new HashMap<>();

        for (Issue issue : issues) {
            String dev = issue.getAssignee() != null ? issue.getAssignee() : "—";
            devStats.computeIfAbsent(dev, k -> new int[]{0, 0});
            devStats.get(dev)[0]++;

            if (issue.isOverdue()) {
                devStats.get(dev)[1]++;
            }
        }

        int total = issues.size();
        long ok = issues.stream().filter(i -> !i.isOverdue()).count();
        int pct = total > 0 ? (int) Math.round((double) ok / total * 100) : 0;
        String flag = pct >= this.config.getTargetPercent() ? "✅" : "⚠️";

        StringBuilder sb = new StringBuilder();
        sb.append("## 📊 Статистика по тестировщикам\n\n");
        sb.append(flag).append(" On-time: ").append(ok).append("/").append(total)
            .append(" = ").append(pct).append("%\n\n");

        devStats.entrySet().stream()
            .sorted(Map.Entry.<String, int[]>comparingByValue(
                Comparator.comparingInt(v -> -v[1])
            ))
            .forEach(entry -> {
                String dev = entry.getKey();
                int[] stat = entry.getValue();
                String bar = stat[1] > 0 ? "🔴" : "🟢";

                sb.append(bar).append(" **").append(dev).append("**: ")
                    .append(stat[0]).append(" задач (").append(stat[1]).append(" просрочено)\n");
            });

        sb.append("\n**P70 истории:**\n");

        for (String status : AppConfig.MONITORED_STATUSES) {
            String emoji = STATUS_EMOJI.getOrDefault(status, "📋");
            OptionalDouble p70 = p70ByStatus.getOrDefault(status, OptionalDouble.empty());
            String p70Str = p70.isPresent() ? p70.getAsDouble() + " раб. дней" : "нет данных";

            sb.append("  ").append(emoji).append(" ").append(status).append(": ").append(p70Str).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    //endregion

    //region Private

    private String buildStatusSection(String status, String emoji, List<Issue> issues, OptionalDouble p70) {

        List<Issue> overdue = issues.stream()
            .filter(Issue::isOverdue)
            .sorted(Comparator.comparingInt((Issue i) -> i.getBusinessDays() == null ? 0 : i.getBusinessDays()).reversed())
            .toList();

        List<Issue> ok = issues.stream()
            .filter(i -> !i.isOverdue())
            .sorted(Comparator.comparingInt((Issue i) -> i.getBusinessDays() == null ? 0 : i.getBusinessDays()).reversed())
            .toList();

        int total = issues.size();
        int okCount = ok.size();
        int pct = total > 0 ? Math.round((float) okCount / total * 100) : 0;
        String flag = pct >= this.config.getTargetPercent() ? "✅" : "⚠️";

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" **").append(status).append("**")
            .append(" — ").append(total).append(" задач  ")
            .append(flag).append(" on-time: ").append(okCount).append("/").append(total)
            .append(" = ").append(pct).append("%\n");

        if (p70.isPresent()) {
            sb.append("  📈 P70 истории: ").append(p70.getAsDouble()).append(" раб. дней\n");
        }

        if (!overdue.isEmpty()) {
            sb.append("  🔴 **Просрочены (> ").append(this.config.getThresholdBusinessDays()).append(" дн.):**\n");

            for (Issue issue : overdue) {
                sb.append("  ").append(this.formatIssueLine(issue, true)).append("\n");
            }
        }

        if (!ok.isEmpty()) {
            sb.append("  🟢 **В норме:**\n");

            for (Issue issue : ok) {
                sb.append("  ").append(this.formatIssueLine(issue, false)).append("\n");
            }
        }

        return sb.toString();
    }

    private String formatIssueLine(Issue issue, boolean bold) {

        String days = issue.getBusinessDays() != null ? String.valueOf(issue.getBusinessDays()) : "?";
        String daysStr = bold ? "**" + days + " дн.**" : days + " дн.";
        String spStr = issue.getStoryPoints() != null ? " | " + issue.getStoryPoints() + " SP" : "";

        return "• [" + issue.getKey() + "](" + issue.getUrl() + ")"
            + " — " + daysStr + "  " + issue.getAssignee() + spStr;
    }

    //endregion
}
