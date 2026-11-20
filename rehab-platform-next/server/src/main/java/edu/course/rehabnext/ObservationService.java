package edu.course.rehabnext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static edu.course.rehabnext.Observation.*;

@Service
class ObservationService {
    private static final Pattern SUBMISSION_KEY = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final WorkspaceAuth auth;
    private final WorkspacePlans plans;
    private final MotionAnalysis analysis = new MotionAnalysis();

    ObservationService(JdbcTemplate database, ObjectMapper json, PlatformTransactionManager manager,
                       WorkspaceAuth auth, WorkspacePlans plans) {
        this.database = database;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
        this.auth = auth;
        this.plans = plans;
    }

    record SavedReport(Report report, boolean created) {}

    private record Submission(long id, String digest, Long traineeId) {}

    private record PlannedSamples(long planItemId, int setNumber, List<Frame> frames) {}

    static final class SubmissionConflict extends RuntimeException {
        SubmissionConflict() { super("这次提交编号已用于其他训练数据，请检查后重试。"); }
    }

    SavedReport save(Capture capture, String key, String authorization) {
        if (key != null && !SUBMISSION_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("提交编号格式无效。");
        }

        if (capture.planItemId() == null) {
            if (capture.setNumber() != null) throw new IllegalArgumentException("训练组次必须关联计划项目。");
            return save(capture, key, key == null ? null : fingerprint(capture), null);
        }

        var item = plans.requireItem(auth.require(authorization), capture.planItemId());
        var setNumber = capture.setNumber();
        if (setNumber == null || setNumber < 1 || setNumber > item.sets()) {
            throw new IllegalArgumentException("训练组次必须在计划规定的范围内。");
        }
        var planned = new Capture(item.exercise(), item.side(), item.mode(), item.target(), capture.frames(),
                item.id(), setNumber);
        String digest = key == null ? null : fingerprint(new PlannedSamples(item.id(), setNumber, capture.frames()));
        return save(planned, key, digest, item.traineeId());
    }

    private SavedReport save(Capture capture, String key, String digest, Long traineeId) {
        if (key != null) {
            var prior = findSubmission(key);
            if (prior.isPresent()) return reuse(prior.get(), digest, traineeId);
        }

        Result result = analysis.analyze(capture);
        try {
            var report = transactions.execute(status -> insert(capture, result, key, digest, traineeId));
            return new SavedReport(Objects.requireNonNull(report), true);
        } catch (DuplicateKeyException collision) {
            // The insert has rolled back before querying the submission that won the race.
            if (key == null) throw collision;
            return reuse(findSubmission(key).orElseThrow(() -> collision), digest, traineeId);
        }
    }

    private Optional<Submission> findSubmission(String key) {
        return database.query("SELECT id, capture_digest, trainee_id FROM training_observation WHERE submission_key = ?",
                        (row, index) -> new Submission(row.getLong("id"), row.getString("capture_digest"),
                                row.getObject("trainee_id", Long.class)), key)
                .stream().findFirst();
    }

    private SavedReport reuse(Submission prior, String digest, Long traineeId) {
        if (!Objects.equals(prior.traineeId(), traineeId) || !Objects.equals(prior.digest(), digest)) {
            throw new SubmissionConflict();
        }
        Report report = findHeader(prior.id()).map(this::withRepetitions)
                .orElseThrow(() -> new IllegalStateException("找不到已提交的训练报告。"));
        return new SavedReport(report, false);
    }

    private String fingerprint(Object payload) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(encode(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法计算训练数据摘要。", error);
        }
    }

    private Report insert(Capture capture, Result result, String key, String digest, Long traineeId) {
        Target target = "target".equals(capture.mode()) ? capture.target() : null;
        String targetJson = target == null ? null : encode(target);
        String frameJson = encode(capture.frames());
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var id = new GeneratedKeyHolder();
        database.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO training_observation
                      (exercise_code, training_side, evaluation_mode, target_json, frames_json, rules_version,
                       valid_frames, total_frames, interrupted_attempts, duration_seconds, created_at,
                       submission_key, capture_digest, plan_item_id, set_number, trainee_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"ID"});
            statement.setString(1, capture.exercise());
            statement.setString(2, capture.side());
            statement.setString(3, capture.mode());
            statement.setString(4, targetJson);
            statement.setString(5, frameJson);
            statement.setString(6, RULES_VERSION);
            statement.setInt(7, result.validFrames());
            statement.setInt(8, result.totalFrames());
            statement.setInt(9, result.interruptedAttempts());
            statement.setDouble(10, result.duration());
            statement.setObject(11, createdAt);
            statement.setString(12, key);
            statement.setString(13, digest);
            statement.setObject(14, capture.planItemId());
            statement.setObject(15, capture.setNumber());
            statement.setObject(16, traineeId);
            return statement;
        }, id);
        long observationId = Objects.requireNonNull(id.getKey()).longValue();
        insertRepetitions(observationId, result.repetitions());
        return new Report(observationId, capture.exercise(), capture.side(), capture.mode(), target, RULES_VERSION,
                result.validFrames(), result.totalFrames(), result.interruptedAttempts(), result.duration(),
                createdAt, result.repetitions(), capture.planItemId(), capture.setNumber(), traineeId);
    }

    Optional<Report> find(long id, String authorization) {
        return findHeader(id).map(header -> {
            if (header.traineeId() != null) checkAccess(auth.require(authorization), header.traineeId());
            return withRepetitions(header);
        });
    }

    List<Summary> list(String authorization) {
        var actor = auth.require(authorization);
        return database.query("""
                SELECT o.id, o.plan_item_id, o.set_number, o.trainee_id, a.display_name, p.title,
                       t.guide_id, o.exercise_code, o.training_side, o.evaluation_mode, o.created_at, o.review_status,
                       (SELECT COUNT(*) FROM observation_repetition r WHERE r.observation_id = o.id) repetition_count
                FROM training_observation o
                JOIN workspace_plan_item i ON i.id = o.plan_item_id
                JOIN workspace_plan p ON p.id = i.plan_id
                JOIN workspace_trainee t ON t.account_id = o.trainee_id
                JOIN workspace_account a ON a.id = o.trainee_id
                ORDER BY o.created_at DESC, o.id DESC
                """, (row, index) -> {
            long traineeId = row.getLong("trainee_id");
            if (!auth.canAccessTrainee(actor, traineeId, row.getObject("guide_id", Long.class))) return null;
            return new Summary(row.getLong("id"), row.getLong("plan_item_id"), row.getInt("set_number"),
                    traineeId, row.getString("display_name"), row.getString("title"), row.getString("exercise_code"),
                    row.getString("training_side"), row.getString("evaluation_mode"),
                    row.getObject("created_at", OffsetDateTime.class), row.getInt("repetition_count"),
                    ReviewStatus.valueOf(row.getString("review_status")));
        }).stream().filter(Objects::nonNull).toList();
    }

    private Optional<Report> findHeader(long id) {
        return database.query("""
                SELECT id,exercise_code,training_side,evaluation_mode,target_json,rules_version,
                       valid_frames,total_frames,interrupted_attempts,duration_seconds,created_at,
                       plan_item_id,set_number,trainee_id
                FROM training_observation WHERE id = ?
                """, (row, index) -> new Report(row.getLong("id"), row.getString("exercise_code"),
                row.getString("training_side"), row.getString("evaluation_mode"), decodeTarget(row.getString("target_json")),
                row.getString("rules_version"), row.getInt("valid_frames"), row.getInt("total_frames"),
                row.getInt("interrupted_attempts"), row.getDouble("duration_seconds"),
                row.getObject("created_at", OffsetDateTime.class), List.of(),
                row.getObject("plan_item_id", Long.class), row.getObject("set_number", Integer.class),
                row.getObject("trainee_id", Long.class)), id).stream().findFirst();
    }

    private Report withRepetitions(Report header) {
        return new Report(header.id(), header.exercise(), header.side(), header.mode(), header.target(),
                header.rulesVersion(), header.validFrames(), header.totalFrames(), header.interruptedAttempts(),
                header.duration(), header.createdAt(), repetitions(header.id()),
                header.planItemId(), header.setNumber(), header.traineeId());
    }

    private List<Repetition> repetitions(long id) {
        return database.query("""
                SELECT peak_degrees, exercise_seconds, hold_seconds, start_seconds, end_seconds,
                       amplitude_met, tempo_met, hold_met
                FROM observation_repetition WHERE observation_id = ? ORDER BY repetition_number
                """, (row, index) -> readRepetition(row), id);
    }

    Review review(long id, String authorization) {
        var actor = auth.require(authorization);
        return readReview(id, actor);
    }

    Review updateEvent(long id, EventReviewRequest request, String authorization) {
        var actor = auth.require(authorization);
        return transactions.execute(transaction -> {
            lockReview(id, actor);
            var all = repetitions(id);
            var number = request.repetition();
            if (number == null || number < 1 || number > all.size() ||
                    !Boolean.FALSE.equals(metricResult(all.get(number - 1).checks(), request.metric()))) {
                throw new IllegalArgumentException("只能复核报告中未达标的指标。");
            }
            if (!"CONFIRMED".equals(request.decision()) && !"DISMISSED".equals(request.decision())) {
                throw new IllegalArgumentException("请选择有效的复核判断。");
            }

            var note = reviewNote(request.note());
            database.update("""
                    INSERT INTO observation_event_review
                      (observation_id, repetition_number, metric, decision, note, author_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, number, request.metric(), request.decision(), note, actor.id());
            database.update("UPDATE training_observation SET review_status = 'IN_REVIEW' WHERE id = ?", id);
            return readReview(id, actor);
        });
    }

    Review updateReview(long id, ReviewRequest request, String authorization) {
        var actor = auth.require(authorization);
        return transactions.execute(transaction -> {
            lockReview(id, actor);
            var note = reviewNote(request.note());
            var status = ReviewStatus.IN_REVIEW;
            if (request.finalizeReview()) {
                if (note.isEmpty()) throw new IllegalArgumentException("完成复核前请填写总体意见。");
                long required = repetitions(id).stream()
                        .map(Repetition::checks)
                        .flatMap(checks -> Stream.of(checks.amplitude(), checks.tempo(), checks.hold()))
                        .filter(Boolean.FALSE::equals)
                        .count();
                long reviewed = readReview(id, actor).events().size();
                if (reviewed != required) throw new IllegalArgumentException("完成复核前请逐项处理未达标指标。");
                status = ReviewStatus.FINALIZED;
            }
            database.update("""
                    INSERT INTO observation_report_review (observation_id, review_status, note, author_id)
                    VALUES (?, ?, ?, ?)
                    """, id, status.name(), note, actor.id());
            database.update("UPDATE training_observation SET review_status = ?, review_note = ? WHERE id = ?",
                    status.name(), note, id);
            return readReview(id, actor);
        });
    }

    private static Boolean metricResult(Checks checks, String metric) {
        if (metric == null) return null;
        return switch (metric) {
            case "amplitude" -> checks.amplitude();
            case "tempo" -> checks.tempo();
            case "hold" -> checks.hold();
            default -> null;
        };
    }

    private static String reviewNote(String value) {
        String note = value == null ? "" : value.strip();
        if (note.length() > 2000) throw new IllegalArgumentException("复核意见不能超过 2000 字符。");
        return note;
    }

    private record ReviewHeader(long traineeId, ReviewStatus status) {}

    private void lockReview(long id, WorkspaceAuth.Actor actor) {
        var header = database.query("""
                SELECT trainee_id, review_status FROM training_observation
                WHERE id = ? AND trainee_id IS NOT NULL FOR UPDATE
                """, (row, index) -> new ReviewHeader(row.getLong("trainee_id"),
                ReviewStatus.valueOf(row.getString("review_status"))), id)
                .stream().findFirst().orElseThrow(ObservationService::reviewNotFound);
        if (!"GUIDE".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有负责指导的老师可编辑复核。");
        }
        checkAccess(actor, header.traineeId());
        if (header.status() == ReviewStatus.FINALIZED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "此报告已经完成复核。");
        }
    }

    private record ReviewRow(long traineeId, ReviewStatus status, String note, ReviewedEvent event) {}

    private Review readReview(long id, WorkspaceAuth.Actor actor) {
        var rows = database.query("""
                SELECT o.trainee_id, o.review_status, o.review_note, r.repetition_number, r.metric, r.decision, r.note
                FROM training_observation o
                LEFT JOIN observation_event_review r ON r.observation_id = o.id AND r.id = (
                    SELECT MAX(recent.id) FROM observation_event_review recent
                    WHERE recent.observation_id = r.observation_id AND recent.repetition_number = r.repetition_number
                      AND recent.metric = r.metric
                )
                WHERE o.id = ? AND o.trainee_id IS NOT NULL
                ORDER BY r.repetition_number, CASE r.metric WHEN 'amplitude' THEN 1 WHEN 'tempo' THEN 2 ELSE 3 END
                """, (row, index) -> {
            var metric = row.getString("metric");
            var event = metric == null ? null : new ReviewedEvent(row.getInt("repetition_number"), metric,
                    row.getString("decision"), row.getString("note"));
            return new ReviewRow(row.getLong("trainee_id"), ReviewStatus.valueOf(row.getString("review_status")),
                    row.getString("review_note"), event);
        }, id);
        if (rows.isEmpty()) throw reviewNotFound();

        var first = rows.get(0);
        checkAccess(actor, first.traineeId());
        return new Review(first.status(), first.note(),
                rows.stream().map(ReviewRow::event).filter(Objects::nonNull).toList());
    }

    private void checkAccess(WorkspaceAuth.Actor actor, long traineeId) {
        if (!auth.canAccessTrainee(actor, traineeId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您无权查看此训练报告。");
        }
    }

    private static ResponseStatusException reviewNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到可复核的训练报告。");
    }

    private void insertRepetitions(long id, List<Repetition> repetitions) {
        for (int index = 0; index < repetitions.size(); index++) {
            var repetition = repetitions.get(index);
            database.update("""
                    INSERT INTO observation_repetition
                      (observation_id,repetition_number,peak_degrees,exercise_seconds,hold_seconds,start_seconds,
                       end_seconds,amplitude_met,tempo_met,hold_met)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, index + 1, repetition.peak(), repetition.seconds(), repetition.holdSeconds(),
                    repetition.start(), repetition.end(), repetition.checks().amplitude(), repetition.checks().tempo(),
                    repetition.checks().hold());
        }
    }

    private static Repetition readRepetition(ResultSet row) throws SQLException {
        var checks = new Checks(row.getObject("amplitude_met", Boolean.class),
                row.getObject("tempo_met", Boolean.class), row.getObject("hold_met", Boolean.class));
        return new Repetition(row.getDouble("peak_degrees"), row.getDouble("exercise_seconds"),
                row.getDouble("hold_seconds"), row.getDouble("start_seconds"), row.getDouble("end_seconds"), checks);
    }

    private String encode(Object object) {
        try {
            return json.writeValueAsString(object);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法编码观察数据。", error);
        }
    }

    private Target decodeTarget(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, Target.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取历史训练目标。", error);
        }
    }
}
