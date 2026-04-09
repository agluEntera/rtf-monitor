-- Looker Studio: история закрытых задач для перцентилей и трендов
-- Все 3 статуса в одной таблице (UNION → одна строка на статус)
-- Используется для графика распределения времени и трендов

SELECT
    i.IssueKey                                              AS issue_key,
    i.Summary                                               AS summary,
    i.TypeName                                              AS issue_type,
    i.Developer                                             AS developer,
    i.SP                                                    AS story_points,
    s.current_status                                        AS final_status,
    'Ready for Testing'                                     AS status_name,
    ROUND(`Ready for Testing`, 1)                           AS hours_in_status,
    ROUND(`Ready for Testing` / 24 * 5 / 7, 1)             AS business_days,
    CASE
        WHEN ROUND(`Ready for Testing` / 24 * 5 / 7) <= 4
        THEN 'В норме' ELSE 'Просрочена'
    END                                                     AS compliance

FROM IssueStatusDurations s
JOIN IssuesInfo i ON i.IssueId = s.Id
WHERE `Ready for Testing` > 0
  AND s.current_status NOT IN ('Ready for Testing')

UNION ALL

SELECT
    i.IssueKey,
    i.Summary,
    i.TypeName,
    i.Developer,
    i.SP,
    s.current_status,
    'Ready for Review'                                      AS status_name,
    ROUND(`Ready for Review`, 1),
    ROUND(`Ready for Review` / 24 * 5 / 7, 1),
    CASE
        WHEN ROUND(`Ready for Review` / 24 * 5 / 7) <= 4
        THEN 'В норме' ELSE 'Просрочена'
    END

FROM IssueStatusDurations s
JOIN IssuesInfo i ON i.IssueId = s.Id
WHERE `Ready for Review` > 0
  AND s.current_status NOT IN ('Ready for Review')

UNION ALL

SELECT
    i.IssueKey,
    i.Summary,
    i.TypeName,
    i.Developer,
    i.SP,
    s.current_status,
    'In Testing'                                            AS status_name,
    ROUND(`In Testing`, 1),
    ROUND(`In Testing` / 24 * 5 / 7, 1),
    CASE
        WHEN ROUND(`In Testing` / 24 * 5 / 7) <= 4
        THEN 'В норме' ELSE 'Просрочена'
    END

FROM IssueStatusDurations s
JOIN IssuesInfo i ON i.IssueId = s.Id
WHERE `In Testing` > 0
  AND s.current_status NOT IN ('In Testing')

ORDER BY business_days DESC
