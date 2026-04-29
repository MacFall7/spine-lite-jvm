package net.m87.spinelite.service;

import java.util.List;
import java.util.Optional;
import net.m87.spinelite.config.EndpointConfig;
import net.m87.spinelite.model.GovernanceDecision;
import net.m87.spinelite.model.GovernanceViolation;
import net.m87.spinelite.model.GovernedCallRequest;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.model.ToolManifest;
import org.springframework.stereotype.Component;

/**
 * Pure governance decision point. No I/O, no logging, no side effects. Returns ALLOW iff every
 * fail-closed check passes.
 */
@Component
public class GovernanceKernel {

  private final ManifestRegistry registry;
  private final EndpointConfig endpointConfig;

  public GovernanceKernel(ManifestRegistry registry, EndpointConfig endpointConfig) {
    this.registry = registry;
    this.endpointConfig = endpointConfig;
  }

  public GovernanceDecision evaluate(GovernedCallRequest req) {
    Optional<LoadedManifest> resolved = registry.resolve(req.agentId(), req.toolManifestId());
    if (resolved.isEmpty()) {
      if (registry.isRegistered(req.toolManifestId())) {
        return new GovernanceDecision.Deny(
            List.of(
                new GovernanceViolation(
                    GovernanceViolation.MANIFEST_AGENT_MISMATCH,
                    "Manifest '"
                        + req.toolManifestId()
                        + "' is not bound to agent '"
                        + req.agentId()
                        + "'",
                    "tool_manifest_id")));
      }
      return new GovernanceDecision.Deny(
          List.of(
              new GovernanceViolation(
                  GovernanceViolation.MANIFEST_NOT_FOUND,
                  "Manifest '" + req.toolManifestId() + "' is not registered",
                  "tool_manifest_id")));
    }

    LoadedManifest loaded = resolved.get();
    ToolManifest manifest = loaded.manifest();

    String currentHash = registry.computeHash(manifest);
    if (!currentHash.equals(loaded.loadTimeHash())) {
      return new GovernanceDecision.Deny(
          List.of(
              new GovernanceViolation(
                  GovernanceViolation.MANIFEST_INTEGRITY_FAILURE,
                  "Manifest hash does not match value captured at load time",
                  "tool_manifest_id")));
    }

    if (endpointConfig.forbiddenSet().contains(manifest.effectClass())) {
      return new GovernanceDecision.Deny(
          List.of(
              new GovernanceViolation(
                  GovernanceViolation.EFFECT_CLASS_FORBIDDEN,
                  "Effect class '"
                      + manifest.effectClass()
                      + "' is not permitted by this endpoint",
                  "effect_class")));
    }

    if (req.prompt().length() > manifest.maxPromptChars()) {
      return new GovernanceDecision.Deny(
          List.of(
              new GovernanceViolation(
                  GovernanceViolation.PROMPT_SIZE_VIOLATION,
                  "Prompt length "
                      + req.prompt().length()
                      + " exceeds manifest max_prompt_chars "
                      + manifest.maxPromptChars(),
                  "prompt")));
    }

    return new GovernanceDecision.Allow(loaded);
  }
}
