package edu.course.rehabnext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static edu.course.rehabnext.Observation.*;

final class MotionAnalysis {
    private static final Set<String> EXERCISES = Set.of("shoulder_flexion", "shoulder_abduction", "elbow_flexion");
    private static final double VISIBILITY = .65;
    private static final double MAX_GAP = .8;

    Result analyze(Capture capture) {
        validate(capture);
        Target target = "target".equals(capture.mode()) ? capture.target() : null;
        var observer = new CycleObserver(target);
        boolean right = "RIGHT".equals(capture.side());
        boolean elbow = "elbow_flexion".equals(capture.exercise());
        int valid = 0;
        for (var frame : capture.frames()) {
            Double angle = measure(frame, right, elbow);
            if (angle != null) valid++;
            observer.sample(frame.time(), angle);
        }
        int total = capture.frames().size();
        double duration = total == 0 ? 0 : capture.frames().get(total - 1).time();
        int incomplete = observer.interruptions + (observer.start != null ? 1 : 0);
        return new Result(valid, total, incomplete, duration, List.copyOf(observer.repetitions));
    }

    private void validate(Capture capture) {
        validateConfiguration(capture.exercise(), capture.side(), capture.mode(), capture.target());
        if (capture.frames() == null || capture.frames().size() > 6000) {
            throw new IllegalArgumentException("每组最多保存 6000 帧。");
        }
        double previous = -1;
        for (var frame : capture.frames()) {
            if (frame == null || !bounded(frame.time(), 0, 3600) || frame.time() <= previous) {
                throw new IllegalArgumentException("帧时间必须在 0～3600 秒内，并严格递增。");
            }
            if (frame.width() == null || frame.width() < 1 || frame.width() > 8192
                    || frame.height() == null || frame.height() < 1 || frame.height() > 8192
                    || frame.poses() == null || frame.poses() < 0 || frame.poses() > 32
                    || frame.points() == null || frame.points().size() > 33) {
                throw new IllegalArgumentException("画面尺寸、人数或关键点数量无效。");
            }
            for (var point : frame.points()) {
                if (point == null || !finite(point.x()) || !finite(point.y())
                        || !bounded(point.visibility(), 0, 1)
                        || (point.presence() != null && !bounded(point.presence(), 0, 1))) {
                    throw new IllegalArgumentException("关键点坐标或置信度无效。");
                }
            }
            previous = frame.time();
        }
    }

    static void validateConfiguration(String exercise, String side, String mode, Target target) {
        if (exercise == null || !EXERCISES.contains(exercise)) {
            throw new IllegalArgumentException("请选择支持的训练动作。");
        }
        if (!"LEFT".equals(side) && !"RIGHT".equals(side)) {
            throw new IllegalArgumentException("请选择左侧或右侧。");
        }
        if (!"observe".equals(mode) && !"target".equals(mode)) {
            throw new IllegalArgumentException("请选择观察或目标比较模式。");
        }
        if ("target".equals(mode)) validateTarget(target);
    }

    private static void validateTarget(Target target) {
        if (target == null || !bounded(target.angleMin(), 0, 180) || !bounded(target.angleMax(), 0, 180)
                || target.angleMin() >= target.angleMax() || !bounded(target.cycleMin(), .6, 30)
                || !bounded(target.cycleMax(), .6, 30) || target.cycleMin() > target.cycleMax()
                || !bounded(target.hold(), 0, 10) || target.hold() > target.cycleMax()) {
            throw new IllegalArgumentException("请填写有效的目标幅度、节奏范围和保持时间。");
        }
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static boolean bounded(Double value, double minimum, double maximum) {
        return finite(value) && value >= minimum && value <= maximum;
    }

    private static boolean usable(Point point) {
        return point.visibility() >= VISIBILITY && (point.presence() == null || point.presence() >= VISIBILITY)
                && bounded(point.x(), .01, .99) && bounded(point.y(), .01, .99);
    }

    private Double measure(Frame frame, boolean right, boolean elbowExercise) {
        if (frame.poses() != 1 || frame.points().size() != 33) return null;
        Point shoulder = frame.points().get(right ? 12 : 11);
        Point elbow = frame.points().get(right ? 14 : 13);
        Point wrist = frame.points().get(right ? 16 : 15);
        Point hip = frame.points().get(right ? 24 : 23);
        if (!usable(shoulder) || !usable(elbow) || !usable(wrist) || !usable(hip)) return null;

        Vector upperArm = displacement(shoulder, elbow, frame);
        Vector forearm = displacement(elbow, wrist, frame);
        Vector trunk = displacement(shoulder, hip, frame);
        if (upperArm.length() < 12 || forearm.length() < 10 || trunk.length() < 20) return null;

        return elbowExercise ? angle(upperArm, forearm) : angle(trunk, upperArm);
    }

    private static Vector displacement(Point from, Point to, Frame frame) {
        return new Vector((to.x() - from.x()) * frame.width(), (to.y() - from.y()) * frame.height());
    }

    private static double angle(Vector left, Vector right) {
        double cosine = (left.x * right.x + left.y * right.y) / (left.length() * right.length());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
    }

    private record Vector(double x, double y) {
        double length() { return Math.hypot(x, y); }
    }

    private record Sample(double time, double angle) {}

    private static final class CycleObserver {
        final Target target;
        final Deque<Sample> calibration = new ArrayDeque<>();
        final List<Repetition> repetitions = new ArrayList<>();
        Double baseline, smooth, lastFrame, lastValid, start;
        Double returningSince, restSince, holdSince;
        double peak, maxHold;
        int interruptions;

        CycleObserver(Target target) { this.target = target; }

        void sample(double time, Double angle) {
            double elapsed = lastFrame == null ? 0 : time - lastFrame;
            lastFrame = time;
            if (lastValid != null && time - lastValid > MAX_GAP) reset();
            if (angle == null) {
                returningSince = restSince = holdSince = null;
                calibration.clear();
                return;
            }
            lastValid = time;
            double alpha = 1 - Math.exp(-Math.min(elapsed, .2) / .12);
            smooth = smooth == null ? angle : smooth + alpha * (angle - smooth);
            if (baseline == null) {
                calibrate(time);
                return;
            }
            if (start == null) {
                if (smooth >= baseline + 12) {
                    start = time;
                    peak = smooth;
                    maxHold = 0;
                    returningSince = restSince = holdSince = null;
                }
                return;
            }
            if (time - start > 30) {
                reset();
                return;
            }
            peak = Math.max(peak, smooth);
            updateHold(time);
            if (peak - smooth < 6) {
                returningSince = restSince = null;
                return;
            }
            if (returningSince == null) returningSince = time;
            if (time - returningSince < .15 || smooth > baseline + 6) {
                restSince = null;
                return;
            }
            if (restSince == null) restSince = time;
            if (time - restSince >= .2 && time - start >= .6) finish(time);
        }

        private void calibrate(double time) {
            calibration.addLast(new Sample(time, smooth));
            while (time - calibration.getFirst().time() > 1.2) calibration.removeFirst();
            if (calibration.size() < 6 || time - calibration.getFirst().time() < .7) return;

            var values = calibration.stream().map(Sample::angle).sorted().toList();
            int size = values.size();
            if (values.get(size - 1) - values.get(0) <= 6) {
                baseline = (values.get(size / 2) + values.get((size - 1) / 2)) / 2;
                calibration.clear();
            }
        }

        private void updateHold(double time) {
            if (target == null || smooth < target.angleMin() || smooth > target.angleMax()) {
                holdSince = null;
                return;
            }
            if (holdSince == null) holdSince = time;
            maxHold = Math.max(maxHold, time - holdSince);
        }

        private void finish(double time) {
            double seconds = time - start;
            Checks checks = target == null ? new Checks(null, null, null)
                    : new Checks(peak >= target.angleMin() && peak <= target.angleMax(),
                    seconds >= target.cycleMin() && seconds <= target.cycleMax(),
                    target.hold() == 0 || (maxHold > 0 && maxHold >= target.hold() - .15));
            repetitions.add(new Repetition(rounded(peak, 100), rounded(seconds, 1000),
                    rounded(maxHold, 1000), rounded(start, 1000), rounded(time, 1000), checks));
            start = null;
        }

        private void reset() {
            if (start != null) interruptions++;
            baseline = smooth = start = returningSince = restSince = holdSince = null;
            calibration.clear();
        }

        private static double rounded(double value, int scale) {
            return Math.round(value * scale) / (double) scale;
        }
    }
}
