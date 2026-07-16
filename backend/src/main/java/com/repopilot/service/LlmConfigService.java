package com.repopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.config.AppProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LlmConfigService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFIG_PATH = Path.of("data", "llm-config.json");

    private final AppProperties defaults;
    private volatile LlmSettings settings;
    private volatile boolean loadedFromFile;

    public LlmConfigService(AppProperties defaults) {
        this.defaults = defaults;
        reload();
    }

    public synchronized LlmSettings current() {
        return settings;
    }

    public synchronized void reload() {
        LlmSettings fromDefaults = fromProperties(defaults);
        if (!Files.isRegularFile(CONFIG_PATH)) {
            settings = fromDefaults;
            loadedFromFile = false;
            return;
        }
        try {
            StoredConfig stored = MAPPER.readValue(Files.readString(CONFIG_PATH), StoredConfig.class);
            settings = merge(fromDefaults, stored);
            loadedFromFile = true;
        } catch (Exception ex) {
            settings = fromDefaults;
            loadedFromFile = false;
        }
    }

    public synchronized Map<String, Object> publicView() {
        LlmSettings current = settings;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", hasApiKey(current));
        view.put("model", blankToDefault(current.model(), defaults.llm().model()));
        view.put("baseUrl", blankToDefault(current.baseUrl(), defaults.llm().baseUrl()));
        view.put("provider", providerLabel(view.get("baseUrl").toString()));
        view.put("hasApiKey", hasApiKey(current));
        view.put("apiKeyMasked", maskApiKey(current.apiKey()));
        view.put("httpReferer", blankToDefault(current.httpReferer(), defaults.llm().httpReferer()));
        view.put("appTitle", blankToDefault(current.appTitle(), defaults.llm().appTitle()));
        view.put("source", loadedFromFile ? "ui" : "env");
        return view;
    }

    public synchronized Map<String, Object> update(Map<String, Object> body) {
        LlmSettings current = settings;
        String apiKey = current.apiKey();
        if (Boolean.TRUE.equals(body.get("clearApiKey"))) {
            apiKey = "";
        } else {
            String incomingKey = str(body.get("apiKey"));
            if (!incomingKey.isBlank()) {
                apiKey = incomingKey;
            }
        }

        LlmSettings updated = new LlmSettings(
                apiKey,
                firstNonBlank(str(body.get("baseUrl")), current.baseUrl(), defaults.llm().baseUrl()),
                firstNonBlank(str(body.get("model")), current.model(), defaults.llm().model()),
                firstNonBlank(str(body.get("httpReferer")), current.httpReferer(), defaults.llm().httpReferer()),
                firstNonBlank(str(body.get("appTitle")), current.appTitle(), defaults.llm().appTitle())
        );
        persist(updated);
        settings = updated;
        loadedFromFile = true;
        return publicView();
    }

    public String providerLabel(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (base.contains("openrouter")) {
            return "OpenRouter";
        }
        if (base.contains("openai")) {
            return "OpenAI";
        }
        return "custom";
    }

    private void persist(LlmSettings updated) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            StoredConfig stored = new StoredConfig(
                    updated.apiKey(),
                    updated.baseUrl(),
                    updated.model(),
                    updated.httpReferer(),
                    updated.appTitle()
            );
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(CONFIG_PATH.toFile(), stored);
        } catch (Exception ex) {
            throw new IllegalStateException("保存 LLM 配置失败: " + ex.getMessage(), ex);
        }
    }

    private static LlmSettings fromProperties(AppProperties properties) {
        return new LlmSettings(
                properties.llm().apiKey(),
                properties.llm().baseUrl(),
                properties.llm().model(),
                properties.llm().httpReferer(),
                properties.llm().appTitle()
        );
    }

    private static LlmSettings merge(LlmSettings defaults, StoredConfig stored) {
        return new LlmSettings(
                firstNonBlank(stored.apiKey(), defaults.apiKey()),
                firstNonBlank(stored.baseUrl(), defaults.baseUrl()),
                firstNonBlank(stored.model(), defaults.model()),
                firstNonBlank(stored.httpReferer(), defaults.httpReferer()),
                firstNonBlank(stored.appTitle(), defaults.appTitle())
        );
    }

    private static boolean hasApiKey(LlmSettings settings) {
        return settings.apiKey() != null && !settings.apiKey().isBlank();
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "••••••••";
        }
        return apiKey.substring(0, Math.min(7, apiKey.length())) + "••••" + apiKey.substring(apiKey.length() - 4);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record LlmSettings(String apiKey, String baseUrl, String model, String httpReferer, String appTitle) {}

    private record StoredConfig(String apiKey, String baseUrl, String model, String httpReferer, String appTitle) {}
}
