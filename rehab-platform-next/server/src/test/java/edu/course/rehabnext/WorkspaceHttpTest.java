package edu.course.rehabnext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:workspace_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WorkspaceHttpTest {
    @Autowired MockMvc browser;
    @Autowired ObjectMapper json;

    @Test
    void logoutRevokesOnlyThePresentedSession() throws Exception {
        browser.perform(get("/api/v1/workspace/me")).andExpect(status().isUnauthorized());
        send(post("/api/v1/workspace/login"), Map.of("username", "teacher", "password", "wrong"))
                .andExpect(status().isUnauthorized());

        var guide = login("teacher");
        var otherLogin = login("teacher");
        browser.perform(get("/api/v1/workspace/me").header("Authorization", guide.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("GUIDE"))
                .andExpect(jsonPath("$.username").value("teacher"));
        browser.perform(post("/api/v1/workspace/logout").header("Authorization", guide.bearer()))
                .andExpect(status().isNoContent());
        browser.perform(get("/api/v1/workspace/me").header("Authorization", guide.bearer()))
                .andExpect(status().isUnauthorized());
        browser.perform(get("/api/v1/workspace/me").header("Authorization", otherLogin.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void disablingAnAccountRevokesItsExistingSessions() throws Exception {
        var admin = login("admin");
        var guide = login("teacher");
        var account = Map.of("username", "disable_case", "password", "Demo123!", "displayName", "停用测试",
                "role", "TRAINEE", "guideId", guide.id());
        send(post("/api/v1/workspace/accounts").header("Authorization", guide.bearer()), account)
                .andExpect(status().isForbidden());
        var traineeId = read(send(post("/api/v1/workspace/accounts").header("Authorization", admin.bearer()), account)
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        send(post("/api/v1/workspace/accounts").header("Authorization", admin.bearer()), account)
                .andExpect(status().isConflict());

        var trainee = login("disable_case");
        String accountPath = "/api/v1/workspace/accounts/" + traineeId;
        send(patch(accountPath).header("Authorization", admin.bearer()), Map.of("enabled", false))
                .andExpect(status().isNoContent());
        browser.perform(get("/api/v1/workspace/me").header("Authorization", trainee.bearer()))
                .andExpect(status().isUnauthorized());
        send(post("/api/v1/workspace/login"), Map.of("username", "disable_case", "password", "Demo123!"))
                .andExpect(status().isUnauthorized());
        send(patch(accountPath).header("Authorization", admin.bearer()), Map.of("enabled", true))
                .andExpect(status().isNoContent());
        login("disable_case");
        browser.perform(get("/api/v1/workspace/me").header("Authorization", trainee.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void baselineAccessFollowsCurrentGuideAssignment() throws Exception {
        var admin = login("admin");
        var guide = login("teacher");
        var otherGuide = login("teacher2");
        var traineeId = read(send(post("/api/v1/workspace/accounts").header("Authorization", admin.bearer()),
                Map.of("username", "baseline_case", "password", "Demo123!", "displayName", "基线测试",
                        "role", "TRAINEE", "guideId", guide.id()))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        var path = "/api/v1/workspace/trainees/" + traineeId;
        var baseline = Map.of("side", "LEFT", "note", "左侧，正面机位");

        browser.perform(get("/api/v1/workspace/guides").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].username", hasItem("teacher2")));
        browser.perform(get("/api/v1/workspace/guides").header("Authorization", guide.bearer()))
                .andExpect(status().isForbidden());
        send(put(path + "/baseline").header("Authorization", otherGuide.bearer()), baseline)
                .andExpect(status().isForbidden());
        send(put(path + "/baseline").header("Authorization", guide.bearer()), baseline)
                .andExpect(status().isNoContent());
        send(put(path + "/guide").header("Authorization", admin.bearer()), Map.of("guideId", otherGuide.id()))
                .andExpect(status().isNoContent());
        browser.perform(get("/api/v1/workspace/trainees").header("Authorization", guide.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].username", not(hasItem("baseline_case"))));
        browser.perform(get("/api/v1/workspace/trainees").header("Authorization", otherGuide.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'baseline_case')].side", hasItem("LEFT")))
                .andExpect(jsonPath("$[?(@.username == 'baseline_case')].baseline", hasItem("左侧，正面机位")));
        send(put(path + "/baseline").header("Authorization", guide.bearer()), baseline)
                .andExpect(status().isForbidden());
    }

    @Test
    void aPublishedPlanKeepsAllItemsAndIsVisibleOnlyWithinItsAssignment() throws Exception {
        var guide = login("teacher");
        var trainee = login("trainee");
        var otherGuide = login("teacher2");
        var otherTrainee = login("trainee2");
        var admin = login("admin");
        var payload = plan("多动作计划", trainee.id(), List.of(observeItem(), targetItem(65, 100)));

        send(post("/api/v1/plans").header("Authorization", admin.bearer()), payload)
                .andExpect(status().isForbidden());
        send(post("/api/v1/plans").header("Authorization", otherGuide.bearer()), payload)
                .andExpect(status().isForbidden());
        var created = read(send(post("/api/v1/plans").header("Authorization", guide.bearer()), payload)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].target.angleMin").value(65.0))
                .andExpect(jsonPath("$.scheduledDate").value("2030-06-12"))
                .andReturn());
        var titleOfPlan = "$[?(@.id == " + created.path("id").asLong() + ")].title";
        for (var user : List.of(guide, trainee, admin)) {
            browser.perform(get("/api/v1/plans").header("Authorization", user.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath(titleOfPlan, hasItem("多动作计划")));
        }
        for (var user : List.of(otherGuide, otherTrainee)) {
            browser.perform(get("/api/v1/plans").header("Authorization", user.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath(titleOfPlan).isEmpty());
        }
    }

    @Test
    void invalidTargetRejectsTheWholePlan() throws Exception {
        var guide = login("teacher");
        var trainee = login("trainee");
        var payload = plan("无效目标", trainee.id(), List.of(observeItem(), targetItem(100, 65)));

        send(post("/api/v1/plans").header("Authorization", guide.bearer()), payload)
                .andExpect(status().isBadRequest());
        browser.perform(get("/api/v1/plans").header("Authorization", trainee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].title", not(hasItem("无效目标"))));
    }

    private Session login(String username) throws Exception {
        var body = read(send(post("/api/v1/workspace/login"), Map.of("username", username, "password", "Demo123!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn());
        return new Session(body.path("actor").path("id").asLong(), "Bearer " + body.path("token").asText());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return browser.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private JsonNode read(MvcResult response) throws Exception {
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private static Map<String, Object> plan(String title, long traineeId, List<Map<String, Object>> items) {
        return Map.of("title", title, "traineeId", traineeId, "scheduledDate", "2030-06-12", "items", items);
    }

    private static Map<String, Object> observeItem() {
        return Map.of("exercise", "elbow_flexion", "side", "RIGHT", "mode", "observe", "sets", 1, "reps", 5);
    }

    private static Map<String, Object> targetItem(int min, int max) {
        return Map.of("exercise", "shoulder_abduction", "side", "LEFT", "mode", "target", "sets", 2, "reps", 8,
                "target", Map.of("angleMin", min, "angleMax", max, "cycleMin", 2, "cycleMax", 10, "hold", .5));
    }

    private record Session(long id, String bearer) {}
}
