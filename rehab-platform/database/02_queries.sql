-- Works on MySQL 8 and H2 MySQL mode. Replace example ID 2 with the current trainee ID.

-- 1. Plan details: one plan joins a trainee, guide, items, exercises and rule versions.
SELECT p.id, p.title, p.version, p.scheduled_date,
       u.display_name AS trainee, g.display_name AS guide,
       e.name AS exercise, i.sets, i.repetitions, i.target_min, i.target_max, r.version AS rule_version
FROM training_plan p
JOIN app_user u ON u.id=p.trainee_id
JOIN app_user g ON g.id=p.guide_id
JOIN plan_item i ON i.plan_id=p.id
JOIN exercise_template e ON e.id=i.exercise_id
JOIN rule_version r ON r.id=i.rule_id
WHERE p.trainee_id=2 ORDER BY p.scheduled_date DESC,p.id,i.id;

-- 2. Daily completed sessions and valid repetitions.
SELECT CAST(started_at AS DATE) AS training_date,COUNT(*) AS sessions,
       SUM(total_reps) AS repetitions,SUM(valid_reps) AS valid_repetitions
FROM training_session WHERE trainee_id=2 AND status IN ('COMPLETED','REVIEWED')
GROUP BY CAST(started_at AS DATE) ORDER BY training_date;

-- 3. Per-exercise issue distribution.
SELECT e.name, ev.code, COUNT(*) AS issue_count
FROM issue_event ev
JOIN repetition_result rr ON rr.id=ev.repetition_id
JOIN session_set ss ON ss.id=rr.set_id
JOIN plan_item i ON i.id=ss.plan_item_id
JOIN exercise_template e ON e.id=i.exercise_id
JOIN training_session s ON s.id=ev.session_id
WHERE s.trainee_id=2 GROUP BY e.name,ev.code ORDER BY issue_count DESC;

-- 4. Latest human decision for each event, without overwriting original observations.
SELECT ev.id,ev.event_time,ev.message,rv.decision,rv.note,rv.created_at
FROM issue_event ev
LEFT JOIN review_record rv ON rv.id=(
 SELECT MAX(r.id) FROM review_record r WHERE r.event_id=ev.id
)
WHERE ev.session_id=1 ORDER BY ev.event_time;

-- 5. Plan adjustment history.
SELECT a.id,oldp.id AS old_plan,oldp.version AS old_version,
       newp.id AS new_plan,newp.version AS new_version,a.reason,a.created_at
FROM plan_adjustment a
JOIN training_plan oldp ON oldp.id=a.old_plan_id
JOIN training_plan newp ON newp.id=a.new_plan_id
WHERE oldp.trainee_id=2 ORDER BY a.id;

-- 6. Index demonstration. Compare with schema index idx_session_trainee_time.
EXPLAIN SELECT * FROM training_session WHERE trainee_id=2 ORDER BY started_at DESC;

-- 7. Guiding relationship is the data-ownership boundary.
SELECT s.id,u.display_name,p.title,s.total_reps,s.status
FROM training_session s JOIN app_user u ON u.id=s.trainee_id
JOIN training_plan p ON p.id=s.plan_id
JOIN guide_relation gr ON gr.trainee_id=s.trainee_id
WHERE gr.guide_id=1 ORDER BY s.started_at DESC;
