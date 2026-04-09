-- Looker Studio: текущие задачи в статусах мониторинга
-- Подключение: MySQL → Custom Query
-- Обновляется при каждом открытии дашборда

SELECT
    i.IssueKey                                          AS issue_key,
    i.Summary                                           AS summary,
    i.TypeName                                          AS issue_type,
    i.SP                                                AS story_points,
    i.Developer                                         AS developer,
    s.current_status                                    AS status,
    d.entered_at                                        AS entered_status_at,

    -- Календарные дни в статусе
    DATEDIFF(NOW(), d.entered_at)                       AS calendar_days,

    -- Приближённые рабочие дни (5/7 от календарных)
    ROUND(DATEDIFF(NOW(), d.entered_at) * 5 / 7)       AS business_days,

    -- Флаг просрочки (> 4 рабочих дня)
    CASE
        WHEN ROUND(DATEDIFF(NOW(), d.entered_at) * 5 / 7) > 4
        THEN 'Просрочена'
        ELSE 'В норме'
    END                                                 AS status_flag,

    -- Ссылка на задачу
    CONCAT('https://entera.atlassian.net/browse/', i.IssueKey) AS jira_url

FROM IssueStatusDurations s
JOIN IssuesInfo i ON i.IssueId = s.Id

-- Последний переход в текущий статус
LEFT JOIN (
    SELECT
        c.IssueId,
        c.New                   AS status,
        MAX(c.CreatedDate)      AS entered_at
    FROM DetailedIssuesChangelog c
    WHERE c.Field = 'status'
      AND c.New IN ('Ready for Testing', 'Ready for Review', 'In Testing')
    GROUP BY c.IssueId, c.New
) d ON d.IssueId = s.Id AND d.status = s.current_status

WHERE s.current_status IN ('Ready for Testing', 'Ready for Review', 'In Testing')
ORDER BY business_days DESC
