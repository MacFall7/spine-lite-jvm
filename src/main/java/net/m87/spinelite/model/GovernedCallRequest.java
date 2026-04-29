package net.m87.spinelite.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GovernedCallRequest(
    @NotBlank
        @Pattern(regexp = "^[a-z0-9-]{3,64}$", message = "agent_id must match ^[a-z0-9-]{3,64}$")
        String agentId,
    @NotBlank
        @Pattern(
            regexp = "^[a-z0-9-]{3,64}$",
            message = "tool_manifest_id must match ^[a-z0-9-]{3,64}$")
        String toolManifestId,
    @NotBlank @Size(min = 1, max = 32_000) String prompt,
    @Pattern(
            regexp = "^[A-Za-z0-9._-]{1,128}$",
            message = "model must match ^[A-Za-z0-9._-]{1,128}$")
        String model,
    @Valid Metadata metadata) {

  public record Metadata(String requestId, String traceId) {}
}
