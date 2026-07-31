package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressServiceTest {

    @Test
    void start_createsRunningState() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Processing");

        assertThat(service.isRunning("task1")).isTrue();
    }

    @Test
    void isRunning_returnsFalseForUnknownKey() {
        ProgressService service = new ProgressService();
        assertThat(service.isRunning("unknown")).isFalse();
    }

    @Test
    void setTotal_updatesTotal() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.setTotal("task1", 200);

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("total")).isEqualTo(200);
    }

    @Test
    void setTotal_ignoresUnknownKey() {
        ProgressService service = new ProgressService();
        service.setTotal("unknown", 100);
        assertThat(service.snapshot("unknown").get("status")).isEqualTo("idle");
    }

    @Test
    void setDone_updatesProgress() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.setDone("task1", 50, "Half done");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("done")).isEqualTo(50);
        assertThat(snapshot.get("message")).isEqualTo("Half done");
    }

    @Test
    void setDone_capsAtTotal() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.setDone("task1", 150, "Too many");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("done")).isEqualTo(100);
    }

    @Test
    void setDone_capsAtZero() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.setDone("task1", -10, "Negative");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("done")).isEqualTo(0);
    }

    @Test
    void setDone_ignoresUnknownKey() {
        ProgressService service = new ProgressService();
        service.setDone("unknown", 50, "msg");
        assertThat(service.snapshot("unknown").get("status")).isEqualTo("idle");
    }

    @Test
    void step_incrementsDone() {
        ProgressService service = new ProgressService();
        service.start("task1", 10, "Start");
        service.step("task1", "Step 1");
        service.step("task1", "Step 2");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("done")).isEqualTo(2);
    }

    @Test
    void step_capsAtTotal() {
        ProgressService service = new ProgressService();
        service.start("task1", 2, "Start");
        service.step("task1", "Step 1");
        service.step("task1", "Step 2");
        service.step("task1", "Step 3");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("done")).isEqualTo(2);
    }

    @Test
    void setStage_updatesStage() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.setStage("task1", "Building index");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("stage")).isEqualTo("Building index");
    }

    @Test
    void finish_setsStatusToDone() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.finish("task1", "Completed");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("status")).isEqualTo("done");
        assertThat(snapshot.get("done")).isEqualTo(100);
        assertThat(snapshot.get("message")).isEqualTo("Completed");
    }

    @Test
    void finish_ignoresUnknownKey() {
        ProgressService service = new ProgressService();
        service.finish("unknown", "msg");
        assertThat(service.snapshot("unknown").get("status")).isEqualTo("idle");
    }

    @Test
    void fail_setsStatusToError() {
        ProgressService service = new ProgressService();
        service.fail("task1", "Something went wrong");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("status")).isEqualTo("error");
        assertThat(snapshot.get("message")).isEqualTo("Something went wrong");
    }

    @Test
    void fail_createsStateIfNotExists() {
        ProgressService service = new ProgressService();
        service.fail("newTask", "Failed from start");
        assertThat(service.snapshot("newTask").get("status")).isEqualTo("error");
    }

    @Test
    void idle_removesState() {
        ProgressService service = new ProgressService();
        service.start("task1", 100, "Start");
        service.idle("task1");

        assertThat(service.isRunning("task1")).isFalse();
        assertThat(service.snapshot("task1").get("status")).isEqualTo("idle");
    }

    @Test
    void snapshot_returnsIdleForUnknownKey() {
        ProgressService service = new ProgressService();
        Map<String, Object> snapshot = service.snapshot("unknown");

        assertThat(snapshot.get("status")).isEqualTo("idle");
        assertThat(snapshot.get("progress")).isEqualTo(0);
        assertThat(snapshot.get("total")).isEqualTo(0);
        assertThat(snapshot.get("done")).isEqualTo(0);
    }

    @Test
    void snapshot_calculatesProgressCorrectly() {
        ProgressService service = new ProgressService();
        service.start("task1", 4, "Start");
        service.setDone("task1", 2, "Half");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("progress")).isEqualTo(50.0);
    }

    @Test
    void snapshot_calculatesProgressWithDecimals() {
        ProgressService service = new ProgressService();
        service.start("task1", 3, "Start");
        service.setDone("task1", 1, "Third");

        Map<String, Object> snapshot = service.snapshot("task1");
        assertThat(snapshot.get("progress")).isEqualTo(33.3);
    }
}
