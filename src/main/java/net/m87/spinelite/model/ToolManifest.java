package net.m87.spinelite.model;

import java.util.List;

public record ToolManifest(
    String manifestId,
    String manifestVersion,
    List<String> boundAgentIds,
    EffectClass effectClass,
    List<String> allowedModels,
    int maxPromptChars,
    String systemPrompt,
    RedactionRules redactionRules) {

  public record RedactionRules(boolean stripPiiFromLogs, boolean logPrompt) {}
}
