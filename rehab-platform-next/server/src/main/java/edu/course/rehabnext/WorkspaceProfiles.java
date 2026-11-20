package edu.course.rehabnext;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

import static edu.course.rehabnext.WorkspaceAuth.*;
import static org.springframework.http.HttpStatus.*;

@Service
class WorkspaceProfiles {
    private final JdbcClient database;
    private final WorkspaceAuth auth;

    WorkspaceProfiles(JdbcClient database, WorkspaceAuth auth) {
        this.database = database;
        this.auth = auth;
    }

    record Trainee(long id, String username, String displayName, Long guideId, String side, String baseline,
                   boolean enabled) {}

    record Guide(long id, String username, String displayName, String role, boolean enabled) {}

    record Assignment(Long guideId) {}

    List<Trainee> trainees(Actor actor) {
        if (!ADMIN.equals(actor.role()) && !GUIDE.equals(actor.role())) {
            throw new ResponseStatusException(FORBIDDEN, "当前账号无权查看训练者列表。");
        }
        return database.sql("""
                SELECT account.id, account.username, account.display_name, account.enabled, profile.guide_id,
                       baseline.training_side, baseline.note
                FROM workspace_trainee profile JOIN workspace_account account ON account.id = profile.account_id
                LEFT JOIN workspace_baseline baseline ON baseline.id = (
                    SELECT MAX(history.id) FROM workspace_baseline history WHERE history.trainee_id = profile.account_id
                )
                WHERE (? = 'ADMIN' OR profile.guide_id = ?)
                ORDER BY account.id
                """).params(actor.role(), actor.id()).query((row, index) -> new Trainee(row.getLong("id"),
                row.getString("username"), row.getString("display_name"), row.getObject("guide_id", Long.class),
                row.getString("training_side"), row.getString("note"), row.getBoolean("enabled"))).list();
    }

    List<Guide> guides(Actor actor) {
        requireRole(actor, ADMIN);
        return database.sql("""
                SELECT id, username, display_name, account_role, enabled
                FROM workspace_account WHERE account_role = 'GUIDE' ORDER BY id
                """).query((row, index) -> new Guide(row.getLong("id"), row.getString("username"),
                row.getString("display_name"), row.getString("account_role"), row.getBoolean("enabled"))).list();
    }

    @Transactional
    Actor createAccount(Actor actor, String username, String password, String displayName, String role, Long guideId) {
        requireRole(actor, ADMIN);
        username = normalizeUsername(username);
        displayName = displayName == null ? "" : displayName.strip();
        if (!USERNAME.matcher(username).matches() || displayName.isEmpty() || displayName.length() > 100
                || password == null || password.length() < 8 || password.length() > 128 || password.isBlank()) {
            throw new IllegalArgumentException("请填写有效的用户名、显示名称和 8 至 128 位密码。");
        }
        if (!GUIDE.equals(role) && !TRAINEE.equals(role)) {
            throw new IllegalArgumentException("只能创建训练者或指导者账号。");
        }
        if (guideId != null) {
            if (!TRAINEE.equals(role)) throw new IllegalArgumentException("只有训练者可分配指导者。");
            requireActiveGuide(guideId);
        }

        var hash = newPassword(password);
        var key = new GeneratedKeyHolder();
        try {
            database.sql("""
                    INSERT INTO workspace_account (username, display_name, account_role, password_salt, password_hash)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(username, displayName, role, hash.salt(), hash.hash()).update(key, "ID");
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(CONFLICT, "该用户名已存在。");
        }
        long accountId = Objects.requireNonNull(key.getKey()).longValue();
        if (TRAINEE.equals(role)) {
            database.sql("INSERT INTO workspace_trainee (account_id, guide_id) VALUES (?, ?)")
                    .params(accountId, guideId).update();
            if (guideId != null) recordAssignment(accountId, guideId, actor.id());
        }
        return new Actor(accountId, username, displayName, role);
    }

    @Transactional
    void changeEnabled(Actor actor, long accountId, Boolean enabled) {
        requireRole(actor, ADMIN);
        if (enabled == null) throw new IllegalArgumentException("请选择账号是否启用。");
        int found = database.sql("UPDATE workspace_account SET enabled = ? WHERE id = ? AND account_role <> 'ADMIN'")
                .params(enabled, accountId).update();
        if (found == 0) throw new ResponseStatusException(NOT_FOUND, "未找到训练者或指导者账号。");
        if (!enabled) database.sql("DELETE FROM workspace_login_session WHERE account_id = ?").param(accountId).update();
    }

    @Transactional
    void assignGuide(Actor actor, long traineeId, Long guideId) {
        requireRole(actor, ADMIN);
        var assignment = lockTrainee(traineeId);
        if (Objects.equals(assignment.guideId(), guideId)) return;
        if (guideId != null) requireActiveGuide(guideId);
        database.sql("UPDATE workspace_trainee SET guide_id = ? WHERE account_id = ?")
                .params(guideId, traineeId).update();
        recordAssignment(traineeId, guideId, actor.id());
    }

    @Transactional
    void recordBaseline(Actor actor, long traineeId, String side, String note) {
        var assignment = lockTrainee(traineeId);
        if (TRAINEE.equals(actor.role()) || !auth.canAccessTrainee(actor, traineeId, assignment.guideId())) {
            throw new ResponseStatusException(FORBIDDEN, "当前账号无权更新该训练者的基线。");
        }
        if (!"LEFT".equals(side) && !"RIGHT".equals(side)) throw new IllegalArgumentException("请选择左侧或右侧。");
        String normalizedNote = note == null ? "" : note.strip();
        if (normalizedNote.length() > 2000) throw new IllegalArgumentException("基线备注最多 2000 字。");
        database.sql("""
                INSERT INTO workspace_baseline (trainee_id, author_id, training_side, note) VALUES (?, ?, ?, ?)
                """).params(traineeId, actor.id(), side, normalizedNote).update();
    }

    Assignment lockTrainee(long traineeId) {
        return database.sql("SELECT guide_id FROM workspace_trainee WHERE account_id = ? FOR UPDATE")
                .param(traineeId).query((row, index) -> new Assignment(row.getObject("guide_id", Long.class)))
                .optional().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "未找到训练者。"));
    }

    private void requireActiveGuide(long guideId) {
        boolean enabled = database.sql("SELECT enabled FROM workspace_account WHERE id = ? AND account_role = 'GUIDE' FOR UPDATE")
                .param(guideId).query(Boolean.class).optional().orElse(false);
        if (!enabled) throw new IllegalArgumentException("请选择已启用的指导者。");
    }

    private void recordAssignment(long traineeId, Long guideId, long authorId) {
        database.sql("INSERT INTO workspace_assignment (trainee_id, guide_id, author_id) VALUES (?, ?, ?)")
                .params(traineeId, guideId, authorId).update();
    }
}
