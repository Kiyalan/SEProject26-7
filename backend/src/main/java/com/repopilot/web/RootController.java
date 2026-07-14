package com.repopilot.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping("/")
    Map<String, String> root() {
        return Map.of("status", "ok", "service", "RepoPilot API");
    }
}
