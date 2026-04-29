package net.m87.spinelite.model;

public record GovernanceViolation(String code, String message, String fieldPath) {

  public static final String MANIFEST_NOT_FOUND = "MANIFEST_NOT_FOUND";
  public static final String MANIFEST_AGENT_MISMATCH = "MANIFEST_AGENT_MISMATCH";
  public static final String EFFECT_CLASS_FORBIDDEN = "EFFECT_CLASS_FORBIDDEN";
  public static final String PROMPT_SIZE_VIOLATION = "PROMPT_SIZE_VIOLATION";
  public static final String MANIFEST_INTEGRITY_FAILURE = "MANIFEST_INTEGRITY_FAILURE";
  public static final String MODEL_NOT_ALLOWED = "MODEL_NOT_ALLOWED";
}
