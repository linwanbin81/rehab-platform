package edu.course.rehabnext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static edu.course.rehabnext.Observation.RULES_VERSION;
import static edu.course.rehabnext.Observation.Target;
import static edu.course.rehabnext.WorkspaceAuth.*;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
class WorkspacePlans {
    private final JdbcClient database;
    private final WorkspaceAuth auth;
    private final WorkspaceProfiles profiles;

    WorkspacePlans(JdbcClient database, WorkspaceAuth auth, WorkspaceProfiles profiles) {
        this.database = database;
        this.auth = auth;
        this.profiles = profiles;
    }

    record Plan(long id, String title, long traineeId, String traineeName, LocalDate scheduledDate, List<Item> items) {}

    record Item(long id, String exercise, String side, String mode, int sets, int reps, Target target) {}

    record NewPlan(String title, Long traineeId, LocalDate scheduledDate, List<NewItem> items) {}

    record NewItem(String exercise, String side, String mode, Integer sets, Integer reps, Target target) {}

    record ItemSpec(long id, long planId, long traineeId, String exercise, String side, String mode, int sets, int reps,
                    Target target) {}

    List<Plan> list(Actor actor) {
        return database.sql("""
                SELECT plan.id AS plan_id, plan.title, plan.trainee_id, account.display_name, plan.scheduled_date,
                       item.id AS item_id, item.exercise_code, item.training_side, item.evaluation_mode,
                       item.sets, item.reps, item.angle_min, item.angle_max, item.cycle_min, item.cycle_max, item.hold_seconds
                FROM workspace_plan plan
                JOIN workspace_plan_item item ON item.plan_id = plan.id
                JOIN workspace_trainee profile ON profile.account_id = plan.trainee_id
                JOIN workspace_account account ON account.id = profile.account_id
                WHERE ? = 'ADMIN' OR (? = 'GUIDE' AND profile.guide_id = ?) OR (? = 'TRAINEE' AND plan.trainee_id = ?)
                ORDER BY plan.scheduled_date DESC, plan.id DESC, item.item_number
                """).params(actor.role(), actor.role(), actor.id(), actor.role(), actor.id()).query(this::readPlans);
    }

    @Transactional
    Plan publish(Actor actor, NewPlan input) {
        requireRole(actor, GUIDE);
        String title = input.title() == null ? "" : input.title().strip();
        if (title.isEmpty() || title.length() > 120 || input.traineeId() == null || input.traineeId() <= 0
                || input.scheduledDate() == null) {
            throw new IllegalArgumentException("请填写训练者、计划标题和训练日期，标题最多 120 字。");
        }
        if (input.items() == null || input.items().isEmpty() || input.items().size() > 20) {
            throw new IllegalArgumentException("每份计划须包含 1 至 20 个训练动作。");
        }
        input.items().forEach(WorkspacePlans::validateItem);

        long traineeId = input.traineeId();
        var assignment = profiles.lockTrainee(traineeId);
        if (!auth.canAccessTrainee(actor, traineeId, assignment.guideId())) {
            throw new ResponseStatusException(FORBIDDEN, "仅可为自己负责的训练者发布计划。");
        }
        String traineeName = database.sql("SELECT display_name FROM workspace_account WHERE id = ?")
                .param(traineeId).query(String.class).single();
        Long baselineId = database.sql("SELECT MAX(id) FROM workspace_baseline WHERE trainee_id = ?")
                .param(traineeId).query(Long.class).optional().orElse(null);
        var key = new GeneratedKeyHolder();
        database.sql("""
                INSERT INTO workspace_plan (title, trainee_id, author_id, baseline_id, scheduled_date)
                VALUES (?, ?, ?, ?, ?)
                """).params(title, traineeId, actor.id(), baselineId, input.scheduledDate()).update(key, "ID");
        long planId = Objects.requireNonNull(key.getKey()).longValue();
        var items = new ArrayList<Item>();
        for (var item : input.items()) items.add(insertItem(planId, items.size() + 1, item));
        return new Plan(planId, title, traineeId, traineeName, input.scheduledDate(), List.copyOf(items));
    }

    ItemSpec requireItem(Actor actor, long id) {
        requireRole(actor, TRAINEE);
        return database.sql("""
                SELECT item.id AS item_id, item.plan_id, plan.trainee_id, item.exercise_code, item.training_side,
                       item.evaluation_mode, item.sets, item.reps, item.angle_min, item.angle_max,
                       item.cycle_min, item.cycle_max, item.hold_seconds
                FROM workspace_plan_item item JOIN workspace_plan plan ON plan.id = item.plan_id
                WHERE item.id = ? AND plan.trainee_id = ?
                """).params(id, actor.id()).query((row, index) -> {
            var item = readItem(row);
            return new ItemSpec(item.id(), row.getLong("plan_id"), row.getLong("trainee_id"), item.exercise(),
                    item.side(), item.mode(), item.sets(), item.reps(), item.target());
        }).optional().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "未找到可执行的训练动作。"));
    }

    private List<Plan> readPlans(ResultSet row) throws SQLException {
        var plans = new LinkedHashMap<Long, Plan>();
        while (row.next()) {
            long id = row.getLong("plan_id");
            var plan = plans.get(id);
            if (plan == null) {
                plan = new Plan(id, row.getString("title"), row.getLong("trainee_id"), row.getString("display_name"),
                        row.getObject("scheduled_date", LocalDate.class), new ArrayList<>());
                plans.put(id, plan);
            }
            plan.items().add(readItem(row));
        }
        return List.copyOf(plans.values());
    }

    private static Item readItem(ResultSet row) throws SQLException {
        String mode = row.getString("evaluation_mode");
        Target target = "target".equals(mode) ? new Target(row.getDouble("angle_min"), row.getDouble("angle_max"),
                row.getDouble("cycle_min"), row.getDouble("cycle_max"), row.getDouble("hold_seconds")) : null;
        return new Item(row.getLong("item_id"), row.getString("exercise_code"), row.getString("training_side"), mode,
                row.getInt("sets"), row.getInt("reps"), target);
    }

    private Item insertItem(long planId, int itemNumber, NewItem item) {
        Target target = "target".equals(item.mode()) ? item.target() : new Target(null, null, null, null, null);
        var key = new GeneratedKeyHolder();
        database.sql("""
                INSERT INTO workspace_plan_item (plan_id, item_number, exercise_code, training_side, evaluation_mode,
                    sets, reps, rules_version, angle_min, angle_max, cycle_min, cycle_max, hold_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(planId, itemNumber, item.exercise(), item.side(), item.mode(), item.sets(), item.reps(),
                RULES_VERSION, target.angleMin(), target.angleMax(), target.cycleMin(), target.cycleMax(), target.hold())
                .update(key, "ID");
        return new Item(Objects.requireNonNull(key.getKey()).longValue(), item.exercise(), item.side(), item.mode(),
                item.sets(), item.reps(), "target".equals(item.mode()) ? target : null);
    }

    private static void validateItem(NewItem item) {
        if (item == null || item.sets() == null || item.sets() < 1 || item.sets() > 20
                || item.reps() == null || item.reps() < 1 || item.reps() > 100) {
            throw new IllegalArgumentException("每个训练动作须有 1 至 20 组，每组 1 至 100 次。");
        }
        MotionAnalysis.validateConfiguration(item.exercise(), item.side(), item.mode(), item.target());
    }
}
