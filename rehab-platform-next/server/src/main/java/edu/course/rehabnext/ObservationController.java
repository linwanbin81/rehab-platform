package edu.course.rehabnext;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static edu.course.rehabnext.Observation.*;

@RestController
@RequestMapping("/api/v1")
class ObservationController {
    private final ObservationService observations;

    ObservationController(ObservationService observations) {
        this.observations = observations;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok", "application", "rehab-platform-next", "rulesVersion", RULES_VERSION);
    }

    @PostMapping(value = "/observations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Report> create(@RequestBody Capture capture,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        var saved = observations.save(capture, key, authorization);
        if (!saved.created()) return ResponseEntity.ok(saved.report());
        return ResponseEntity.created(URI.create("/api/v1/observations/" + saved.report().id())).body(saved.report());
    }

    @GetMapping("/observations")
    List<Summary> list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return observations.list(authorization);
    }

    @GetMapping("/observations/{id}")
    ResponseEntity<Report> get(@PathVariable long id,
                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.of(observations.find(id, authorization));
    }

    @GetMapping("/observations/{id}/review")
    Review review(@PathVariable long id,
                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return observations.review(id, authorization);
    }

    @PostMapping("/observations/{id}/event-reviews")
    Review updateEvent(@PathVariable long id, @RequestBody EventReviewRequest request,
                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return observations.updateEvent(id, request, authorization);
    }

    @PostMapping("/observations/{id}/review")
    Review updateReview(@PathVariable long id, @RequestBody ReviewRequest request,
                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return observations.updateReview(id, request, authorization);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> responseError(ResponseStatusException error) {
        var message = error.getReason() == null ? "无法处理此请求。" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "无法读取采集数据，请重试保存。"));
    }

    @ExceptionHandler(ObservationService.SubmissionConflict.class)
    ResponseEntity<Map<String, String>> conflict(ObservationService.SubmissionConflict error) {
        return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
    }
}
