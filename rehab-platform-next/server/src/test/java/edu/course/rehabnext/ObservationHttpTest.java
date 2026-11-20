package edu.course.rehabnext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:camera_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ObservationHttpTest {
    @Autowired MockMvc browser;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;

    @Test
    void completesAndSavesOneKnownShoulderCycle() throws Exception {
        var response = browser.perform(post("/api/v1/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(payload(cycle(false), "target"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repetitions.length()").value(1))
                .andExpect(jsonPath("$.repetitions[0].checks.amplitude").value(true))
                .andExpect(jsonPath("$.repetitions[0].checks.tempo").value(true))
                .andExpect(jsonPath("$.repetitions[0].checks.hold").value(true))
                .andExpect(jsonPath("$.validFrames").value(51))
                .andReturn();
        var created = json.readTree(response.getResponse().getContentAsByteArray());
        long id = created.get("id").asLong();
        assertEquals(1, database.queryForObject(
                "select count(*) from observation_repetition where observation_id = ?", Integer.class, id));
        var restored = browser.perform(get("/api/v1/observations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repetitions.length()").value(1))
                .andExpect(jsonPath("$.totalFrames").value(51))
                .andReturn();
        assertEquals(created, json.readTree(restored.getResponse().getContentAsByteArray()));
    }

    @Test
    void occlusionDiscardsIncompleteCycleAndMarksFramesUnreliable() throws Exception {
        browser.perform(post("/api/v1/observations").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(payload(cycle(true), "observe"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repetitions.length()").value(0))
                .andExpect(jsonPath("$.validFrames").value(38))
                .andExpect(jsonPath("$.totalFrames").value(51));
    }

    @Test
    void unconfiguredTargetsAndDuplicateTimesAreRejected() throws Exception {
        var frame = frame(0, 0, true);
        browser.perform(post("/api/v1/observations").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("exercise", "elbow_flexion", "side", "RIGHT",
                                "mode", "target", "frames", List.of(frame)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        browser.perform(post("/api/v1/observations").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(payload(List.of(frame, frame), "observe"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void positiveHoldGoalNeedsObservedTimeInTargetRange() throws Exception {
        var capture = new HashMap<>(payload(cycle(false), "target"));
        capture.put("target", Map.of("angleMin", 130, "angleMax", 150, "cycleMin", 1,
                "cycleMax", 12, "hold", .1));
        browser.perform(post("/api/v1/observations").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(capture)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repetitions[0].holdSeconds").value(0))
                .andExpect(jsonPath("$.repetitions[0].checks.hold").value(false));
    }

    @Test
    void keyedRetryReturnsTheSavedReportAndRejectsDifferentCapture() throws Exception {
        var key = UUID.randomUUID().toString();
        var original = submit(key, payload(cycle(false), "observe"));
        assertEquals(201, original.getResponse().getStatus());
        var replay = submit(key, payload(cycle(false), "observe"));
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(json.readTree(original.getResponse().getContentAsByteArray()),
                json.readTree(replay.getResponse().getContentAsByteArray()));

        var conflict = submit(key, payload(cycle(true), "observe"));
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals(400, submit("contains spaces", payload(cycle(false), "observe")).getResponse().getStatus());
    }

    @Test
    void preservesAnonymousRetryForAnObservationSavedBeforePlanning() throws Exception {
        String key = UUID.randomUUID().toString();
        var originalCapture = """
                {"exercise":"shoulder_flexion","side":"RIGHT","mode":"observe","target":null,"frames":[]}
                """.strip();
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(originalCapture.getBytes(StandardCharsets.UTF_8)));
        database.update("""
                INSERT INTO training_observation
                  (exercise_code, training_side, evaluation_mode, frames_json, rules_version, valid_frames,
                   total_frames, interrupted_attempts, duration_seconds, created_at, submission_key, capture_digest)
                VALUES ('shoulder_flexion', 'RIGHT', 'observe', '[]', 'camera-rules-1', 0, 0, 0, 0,
                        CURRENT_TIMESTAMP, ?, ?)
                """, key, digest);
        long oldId = database.queryForObject("SELECT id FROM training_observation WHERE submission_key = ?",
                Long.class, key);
        browser.perform(post("/api/v1/observations").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(originalCapture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(oldId))
                .andExpect(jsonPath("$.planItemId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.traineeId").doesNotHaveJsonPath());
    }

    @Test
    void concurrentRetriesCreateExactlyOneObservation() throws Exception {
        long before = database.queryForObject("SELECT COUNT(*) FROM training_observation", Long.class);
        var key = UUID.randomUUID().toString();
        var capture = payload(cycle(false), "target");
        var ready = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return submit(key, capture).getResponse();
            });
            var second = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return submit(key, capture).getResponse();
            });
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(200, 201), java.util.stream.Stream.of(a.getStatus(), b.getStatus()).sorted().toList());
            assertEquals(json.readTree(a.getContentAsByteArray()), json.readTree(b.getContentAsByteArray()));
            assertEquals(before + 1, database.queryForObject("SELECT COUNT(*) FROM training_observation", Long.class));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void anonymousRequestsCannotSubmitAPlannedSetOrListReports() throws Exception {
        var capture = Map.of("planItemId", 1, "setNumber", 1, "frames", List.of());
        browser.perform(post("/api/v1/observations").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(capture)))
                .andExpect(status().isUnauthorized());
        browser.perform(get("/api/v1/observations")).andExpect(status().isUnauthorized());
    }

    @Test
    void plannedCaptureUsesServerTargetAndLimitsReportAccess() throws Exception {
        var trainee = login("trainee");
        var otherTrainee = login("trainee2");
        var guide = login("teacher");
        var planItem = planItem(trainee, guide);
        var capture = plannedCapture(planItem, 2);
        var key = UUID.randomUUID().toString();

        submitPlanned(trainee, key, plannedCapture(planItem, 3)).andExpect(status().isBadRequest());
        submitPlanned(otherTrainee, key, capture).andExpect(status().isNotFound());
        var created = submitPlanned(trainee, key, capture)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exercise").value("shoulder_flexion"))
                .andExpect(jsonPath("$.side").value("RIGHT"))
                .andExpect(jsonPath("$.mode").value("target"))
                .andExpect(jsonPath("$.target.angleMin").value(130))
                .andExpect(jsonPath("$.planItemId").value(planItem))
                .andExpect(jsonPath("$.setNumber").value(2))
                .andExpect(jsonPath("$.traineeId").value(trainee.id()))
                .andExpect(jsonPath("$.repetitions[0].checks.amplitude").value(false))
                .andReturn();
        long id = body(created).get("id").asLong();

        submitPlanned(trainee, key, capture).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        submitPlanned(trainee, key, plannedCapture(planItem, 1)).andExpect(status().isConflict());
        assertEquals(409, submit(key, payload(cycle(false), "observe")).getResponse().getStatus());
        long otherPlanItem = planItem(otherTrainee, login("teacher2"));
        submitPlanned(otherTrainee, key, plannedCapture(otherPlanItem, 2)).andExpect(status().isConflict());

        browser.perform(get("/api/v1/observations/{id}", id)).andExpect(status().isUnauthorized());
        browser.perform(get("/api/v1/observations/{id}", id).header("Authorization", otherTrainee.bearer()))
                .andExpect(status().isForbidden());
        browser.perform(get("/api/v1/observations/{id}", id).header("Authorization", guide.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planItemId").value(planItem));
        browser.perform(get("/api/v1/observations").header("Authorization", guide.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].planTitle").value("Shoulder practice"))
                .andExpect(jsonPath("$[0].repetitionCount").value(1))
                .andExpect(jsonPath("$[0].reviewStatus").value("PENDING"));
        browser.perform(get("/api/v1/observations").header("Authorization", otherTrainee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void guideCanFinalizeOnlyAfterReviewingEveryFailedMetric() throws Exception {
        var trainee = login("trainee");
        var guide = login("teacher");
        var admin = login("admin");
        long planItem = planItem(trainee, guide);
        long id = body(submitPlanned(trainee, UUID.randomUUID().toString(), plannedCapture(planItem, 1))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        browser.perform(get("/api/v1/observations/{id}/review", id).header("Authorization", trainee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        postReview(trainee, id, "event-reviews", event(1, "amplitude", "CONFIRMED"))
                .andExpect(status().isForbidden());
        postReview(admin, id, "review", Map.of("note", "Read-only", "finalize", false))
                .andExpect(status().isForbidden());
        postReview(login("teacher2"), id, "event-reviews", event(1, "amplitude", "CONFIRMED"))
                .andExpect(status().isForbidden());
        postReview(guide, id, "event-reviews", event(1, "tempo", "CONFIRMED"))
                .andExpect(status().isBadRequest());
        postReview(guide, id, "event-reviews", event(2, "amplitude", "CONFIRMED"))
                .andExpect(status().isBadRequest());
        postReview(guide, id, "event-reviews", event(1, "amplitude", "CONFIRMED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_REVIEW"));
        postReview(guide, id, "review", Map.of("note", "Try a shorter hold", "finalize", true))
                .andExpect(status().isBadRequest());
        postReview(guide, id, "event-reviews", event(1, "hold", "CONFIRMED"))
                .andExpect(status().isOk());
        postReview(guide, id, "event-reviews", event(1, "amplitude", "DISMISSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].decision").value("DISMISSED"));
        assertEquals(List.of("CONFIRMED", "DISMISSED"), database.queryForList("""
                SELECT decision FROM observation_event_review
                WHERE observation_id = ? AND metric = 'amplitude' AND author_id = ?
                ORDER BY id
                """, String.class, id, guide.id()));
        postReview(guide, id, "review", Map.of("note", " ", "finalize", true))
                .andExpect(status().isBadRequest());
        postReview(guide, id, "review", Map.of("note", "Try a shorter hold", "finalize", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINALIZED"));
        postReview(guide, id, "event-reviews", event(1, "hold", "DISMISSED"))
                .andExpect(status().isConflict());
        browser.perform(get("/api/v1/observations/{id}/review", id).header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"))
                .andExpect(jsonPath("$.note").value("Try a shorter hold"));
        browser.perform(get("/api/v1/observations/{id}", id).header("Authorization", trainee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.repetitions[0].checks.amplitude").value(false));
    }

    @Test
    void reportAccessFollowsTheCurrentGuideAfterReassignment() throws Exception {
        var trainee = login("trainee");
        var previousGuide = login("teacher");
        var currentGuide = login("teacher2");
        var admin = login("admin");
        long planItem = planItem(trainee, previousGuide);
        var capture = Map.<String, Object>of("planItemId", planItem, "setNumber", 1, "frames", List.of());
        long id = body(submitPlanned(trainee, UUID.randomUUID().toString(), capture)
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        try {
            assignGuide(admin, trainee, currentGuide);
            browser.perform(get("/api/v1/observations/{id}", id).header("Authorization", previousGuide.bearer()))
                    .andExpect(status().isForbidden());
            postReview(previousGuide, id, "review", Map.of("note", "Former guide", "finalize", false))
                    .andExpect(status().isForbidden());
            browser.perform(get("/api/v1/observations").header("Authorization", previousGuide.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            browser.perform(get("/api/v1/observations/{id}", id).header("Authorization", currentGuide.bearer()))
                    .andExpect(status().isOk());
            postReview(currentGuide, id, "review", Map.of("note", "No failed metrics", "finalize", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FINALIZED"))
                    .andExpect(jsonPath("$.events.length()").value(0));
        } finally {
            assignGuide(admin, trainee, previousGuide);
        }
    }

    private record Login(long id, String bearer) {}

    private Login login(String username) throws Exception {
        var response = body(browser.perform(post("/api/v1/workspace/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", "Demo123!"))))
                .andExpect(status().isOk()).andReturn());
        return new Login(response.get("actor").get("id").asLong(), "Bearer " + response.get("token").asText());
    }

    private long planItem(Login trainee, Login guide) throws Exception {
        var target = Map.of("angleMin", 130, "angleMax", 150, "cycleMin", 1, "cycleMax", 12, "hold", .1);
        var item = Map.of("exercise", "shoulder_flexion", "side", "RIGHT", "mode", "target",
                "sets", 2, "reps", 1, "target", target);
        var plan = Map.of("title", "Shoulder practice", "traineeId", trainee.id(), "scheduledDate", "2026-11-20",
                "items", List.of(item));
        return body(browser.perform(post("/api/v1/plans").header("Authorization", guide.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(plan)))
                .andExpect(status().isCreated()).andReturn()).get("items").get(0).get("id").asLong();
    }

    private void assignGuide(Login admin, Login trainee, Login guide) throws Exception {
        browser.perform(put("/api/v1/workspace/trainees/{id}/guide", trainee.id())
                        .header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("guideId", guide.id()))))
                .andExpect(status().isNoContent());
    }

    private Map<String, Object> plannedCapture(long planItem, int setNumber) {
        return Map.of("planItemId", planItem, "setNumber", setNumber, "frames", cycle(false),
                "exercise", "knee_extension", "side", "LEFT", "mode", "observe");
    }

    private ResultActions submitPlanned(Login trainee, String key, Map<String, Object> capture) throws Exception {
        return browser.perform(post("/api/v1/observations").header("Authorization", trainee.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(capture)));
    }

    private ResultActions postReview(Login actor, long observationId, String resource, Map<String, Object> review)
            throws Exception {
        return browser.perform(post("/api/v1/observations/{id}/" + resource, observationId)
                .header("Authorization", actor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(review)));
    }

    private Map<String, Object> event(int repetition, String metric, String decision) {
        return Map.of("repetition", repetition, "metric", metric, "decision", decision, "note", "Checked the recording");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private MvcResult submit(String key, Map<String, Object> capture) throws Exception {
        return browser.perform(post("/api/v1/observations").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(capture))).andReturn();
    }

    private Map<String, Object> payload(List<Map<String, Object>> frames, String mode) {
        return Map.of("exercise", "shoulder_flexion", "side", "RIGHT", "mode", mode, "frames", frames,
                "target", Map.of("angleMin", 60, "angleMax", 110, "cycleMin", 1,
                        "cycleMax", 12, "hold", .25));
    }

    private List<Map<String, Object>> cycle(boolean interrupted) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (int index = 0; index <= 50; index++) {
            double angle = index < 12 ? 0 : index < 21 ? (index - 12) * 10
                    : index < 30 ? 90 : index < 39 ? (39 - index) * 10 : 0;
            frames.add(frame(index / 10d, angle, !interrupted || index < 22 || index > 34));
        }
        return frames;
    }

    private Map<String, Object> frame(double time, double angle, boolean visible) {
        List<Map<String, Double>> points = new ArrayList<>();
        for (int index = 0; index < 33; index++) points.add(point(320, 240, 1));
        double rotation = Math.toRadians(angle);
        points.set(12, point(320, 150, 1));
        points.set(24, point(320, 330, 1));
        points.set(14, point(320 + 70 * Math.sin(rotation), 150 + 70 * Math.cos(rotation), visible ? 1 : .1));
        points.set(16, point(320 + 140 * Math.sin(rotation), 150 + 140 * Math.cos(rotation), 1));
        return Map.of("time", time, "width", 640, "height", 480, "poses", 1, "points", points);
    }

    private Map<String, Double> point(double x, double y, double visibility) {
        return Map.of("x", x / 640, "y", y / 480, "visibility", visibility);
    }
}
