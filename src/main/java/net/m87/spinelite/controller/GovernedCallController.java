package net.m87.spinelite.controller;

import java.util.Map;
import net.m87.spinelite.service.ManifestRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GovernedCallController {

  private final ManifestRegistry registry;

  public GovernedCallController(ManifestRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/healthz")
  public Map<String, Object> healthz() {
    return Map.of("status", "ok", "manifests_loaded", registry.size());
  }
}
