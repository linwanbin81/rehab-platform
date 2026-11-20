package edu.course.rehabnext;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

final class Observation {
    static final String RULES_VERSION = "camera-rules-1";

    record Point(Double x, Double y, Double visibility, Double presence) {}

    record Frame(Double time, Integer width, Integer height, Integer poses, List<Point> points) {}

    record Target(Double angleMin, Double angleMax, Double cycleMin, Double cycleMax, Double hold) {}

    record Capture(String exercise, String side, String mode, Target target, List<Frame> frames,
                   @JsonInclude(JsonInclude.Include.NON_NULL) Long planItemId,
                   @JsonInclude(JsonInclude.Include.NON_NULL) Integer setNumber) {
        Capture(String exercise, String side, String mode, Target target, List<Frame> frames) {
            this(exercise, side, mode, target, frames, null, null);
        }
    }

    record Checks(Boolean amplitude, Boolean tempo, Boolean hold) {}

    record Repetition(double peak, double seconds, double holdSeconds, double start, double end, Checks checks) {}

    record Result(int validFrames, int totalFrames, int interruptedAttempts, double duration,
                  List<Repetition> repetitions) {}

    record Report(long id, String exercise, String side, String mode, Target target, String rulesVersion,
                  int validFrames, int totalFrames, int interruptedAttempts, double duration,
                  OffsetDateTime createdAt, List<Repetition> repetitions,
                  @JsonInclude(JsonInclude.Include.NON_NULL) Long planItemId,
                  @JsonInclude(JsonInclude.Include.NON_NULL) Integer setNumber,
                  @JsonInclude(JsonInclude.Include.NON_NULL) Long traineeId) {}

    record Summary(long id, long planItemId, int setNumber, long traineeId, String traineeName, String planTitle,
                   String exercise, String side, String mode, OffsetDateTime createdAt, int repetitionCount,
                   ReviewStatus reviewStatus) {}

    enum ReviewStatus { PENDING, IN_REVIEW, FINALIZED }

    record EventReviewRequest(Integer repetition, String metric, String decision, String note) {}

    record ReviewRequest(String note, @JsonProperty("finalize") boolean finalizeReview) {}

    record ReviewedEvent(int repetition, String metric, String decision, String note) {}

    record Review(ReviewStatus status, String note, List<ReviewedEvent> events) {}

    private Observation() {}
}
