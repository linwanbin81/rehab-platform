package edu.course.rehabnext;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1")
class WorkspaceController {
    private final WorkspaceAuth auth;
    private final WorkspaceProfiles profiles;
    private final WorkspacePlans plans;

    WorkspaceController(WorkspaceAuth auth, WorkspaceProfiles profiles, WorkspacePlans plans) {
        this.auth = auth;
        this.profiles = profiles;
        this.plans = plans;
    }

    record Credentials(String username, String password) {}

    record NewAccount(String username, String password, String displayName, String role, Long guideId) {}

    record AccountStatus(Boolean enabled) {}

    record GuideAssignment(Long guideId) {}

    record Baseline(String side, String note) {}

    @GetMapping("/workspace/me")
    WorkspaceAuth.Actor me(@RequestHeader(value = AUTHORIZATION, required = false) String authorization) {
        return auth.require(authorization);
    }

    @PostMapping("/workspace/login")
    ResponseEntity<WorkspaceAuth.Login> login(@RequestBody Credentials credentials) {
        var login = auth.login(credentials.username(), credentials.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login);
    }

    @PostMapping("/workspace/logout")
    @ResponseStatus(NO_CONTENT)
    void logout(@RequestHeader(value = AUTHORIZATION, required = false) String authorization) {
        auth.logout(authorization);
    }

    @GetMapping("/workspace/trainees")
    List<WorkspaceProfiles.Trainee> trainees(@RequestHeader(value = AUTHORIZATION, required = false) String authorization) {
        return profiles.trainees(auth.require(authorization));
    }

    @GetMapping("/workspace/guides")
    List<WorkspaceProfiles.Guide> guides(@RequestHeader(value = AUTHORIZATION, required = false) String authorization) {
        return profiles.guides(auth.require(authorization));
    }

    @PostMapping("/workspace/accounts")
    @ResponseStatus(CREATED)
    WorkspaceAuth.Actor createAccount(@RequestHeader(value = AUTHORIZATION, required = false) String authorization,
                                      @RequestBody NewAccount input) {
        return profiles.createAccount(auth.require(authorization), input.username(), input.password(),
                input.displayName(), input.role(), input.guideId());
    }

    @PatchMapping("/workspace/accounts/{id}")
    @ResponseStatus(NO_CONTENT)
    void changeAccountStatus(@RequestHeader(value = AUTHORIZATION, required = false) String authorization,
                             @PathVariable long id, @RequestBody AccountStatus status) {
        profiles.changeEnabled(auth.require(authorization), id, status.enabled());
    }

    @PutMapping("/workspace/trainees/{id}/guide")
    @ResponseStatus(NO_CONTENT)
    void assignGuide(@RequestHeader(value = AUTHORIZATION, required = false) String authorization,
                     @PathVariable long id, @RequestBody GuideAssignment assignment) {
        profiles.assignGuide(auth.require(authorization), id, assignment.guideId());
    }

    @PutMapping("/workspace/trainees/{id}/baseline")
    @ResponseStatus(NO_CONTENT)
    void recordBaseline(@RequestHeader(value = AUTHORIZATION, required = false) String authorization,
                        @PathVariable long id, @RequestBody Baseline baseline) {
        profiles.recordBaseline(auth.require(authorization), id, baseline.side(), baseline.note());
    }

    @GetMapping("/plans")
    List<WorkspacePlans.Plan> listPlans(@RequestHeader(value = AUTHORIZATION, required = false) String authorization) {
        return plans.list(auth.require(authorization));
    }

    @PostMapping("/plans")
    @ResponseStatus(CREATED)
    WorkspacePlans.Plan publishPlan(@RequestHeader(value = AUTHORIZATION, required = false) String authorization,
                                    @RequestBody WorkspacePlans.NewPlan plan) {
        return plans.publish(auth.require(authorization), plan);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> statusError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "无法读取提交数据，请检查后重试。"));
    }
}
