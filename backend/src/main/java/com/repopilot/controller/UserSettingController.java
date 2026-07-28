package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.LlmService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user/setting")
public class UserSettingController {

    private final LlmService llmService;

    public UserSettingController(LlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping("/llmconfig")
    Map<String, Object> getLlmConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthSupport.requireToken(authorization);
        return llmService.contractConfig();
    }

    @PostMapping("/llmconfig/set")
    Map<String, Object> setLlmConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body
    ) {
        AuthSupport.requireToken(authorization);
        return llmService.updateContractConfig(body);
    }
}
