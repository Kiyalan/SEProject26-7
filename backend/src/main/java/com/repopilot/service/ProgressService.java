package com.repopilot.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class ProgressService {

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public void start(String key, int total, String message) {
        State state = new State();
        state.status = "running";
        state.total = Math.max(total, 1);
        state.done = 0;
        state.message = message;
        states.put(key, state);
    }

    public boolean isRunning(String key) {
        State state = states.get(key);
        return state != null && "running".equals(state.status);
    }

    public void setTotal(String key, int total) {
        State state = states.get(key);
        if (state != null) {
            state.total = Math.max(total, 1);
        }
    }

    public void setDone(String key, int done, String message) {
        State state = states.get(key);
        if (state == null) {
            return;
        }
        state.done = Math.min(Math.max(done, 0), state.total);
        state.message = message;
    }

    public void step(String key, String message) {
        State state = states.get(key);
        if (state == null) {
            return;
        }
        state.done = Math.min(state.done + 1, state.total);
        state.message = message;
    }

    public void setStage(String key, String stage) {
        State state = states.get(key);
        if (state != null) {
            state.stage = stage;
        }
    }

    public void finish(String key, String message) {
        State state = states.get(key);
        if (state == null) {
            return;
        }
        state.status = "done";
        state.done = state.total;
        state.message = message;
    }

    public void fail(String key, String message) {
        State state = states.computeIfAbsent(key, k -> new State());
        state.status = "error";
        state.message = message;
    }

    public void idle(String key) {
        states.remove(key);
    }

    public Map<String, Object> snapshot(String key) {
        State state = states.get(key);
        if (state == null) {
            return Map.of(
                    "status", "idle",
                    "progress", 0,
                    "message", "",
                    "stage", "",
                    "total", 0,
                    "done", 0
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", state.status);
        result.put("progress", Math.round(state.done * 1000.0 / state.total) / 10.0);
        result.put("message", state.message);
        result.put("stage", state.stage != null ? state.stage : "");
        result.put("total", state.total);
        result.put("done", state.done);
        return result;
    }

    private static class State {
        String status = "idle";
        int total = 1;
        int done = 0;
        String message = "";
        String stage = "";
    }
}
