package net.m87.spinelite.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.m87.spinelite.model.AuditReceipt;
import net.m87.spinelite.model.EffectClass;
import net.m87.spinelite.model.GovernanceDecision;
import net.m87.spinelite.model.GovernanceViolation;
import net.m87.spinelite.model.LlmResponse;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.model.ToolManifest;
import net.m87.spinelite.service.AuditReceiptService;
import net.m87.spinelite.service.GovernanceKernel;
import net.m87.spinelite.service.LlmClient;
import net.m87.spinelite.service.ManifestRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GovernedCallController.class)
class GovernedCallControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private ManifestRegistry registry;
  @MockBean private GovernanceKernel kernel;
  @MockBean private LlmClient llmClient;
  @MockBean private AuditReceiptService auditService;

  private static ToolManifest manifest() {
    return new ToolManifest(
        "readonly-summarizer",
        "1.0.0",
        List.of("summarizer-v1"),
        EffectClass.READ_ONLY,
        List.of("claude-sonnet-4-6"),
        16000,
        "system prompt text",
        new ToolManifest.RedactionRules(true, false));
  }

  private static AuditReceipt fakeReceipt(String decision, String requestId) {
    return new AuditReceipt(
        "rec-1", requestId, Instant.parse("2026-04-29T14:23:01.456Z"),
        "summarizer-v1", "readonly-summarizer", "sha256:m",
        decision, List.of(), "sha256:p", "sha256:r", "claude-sonnet-4-6", "sha256:rh");
  }

  @Test
  void allowPathReturns200WithReceiptId() throws Exception {
    LoadedManifest loaded = new LoadedManifest(manifest(), "sha256:m");
    when(kernel.evaluate(any())).thenReturn(new GovernanceDecision.Allow(loaded));
    when(llmClient.complete(eq("claude-sonnet-4-6"), eq("system prompt text"), eq("hello")))
        .thenReturn(new LlmResponse("hi back", "claude-sonnet-4-6", "end_turn"));
    when(auditService.recordAllow(any(), any(), eq(loaded), any(), anyLong()))
        .thenReturn(fakeReceipt("ALLOW", "req-1"));

    String body =
        """
        {"agent_id":"summarizer-v1","tool_manifest_id":"readonly-summarizer","prompt":"hello",
         "metadata":{"request_id":"req-1"}}
        """;

    mockMvc
        .perform(post("/v1/governed-call").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("ALLOW"))
        .andExpect(jsonPath("$.request_id").value("req-1"))
        .andExpect(jsonPath("$.receipt_id").value("rec-1"))
        .andExpect(jsonPath("$.response.content").value("hi back"))
        .andExpect(jsonPath("$.response.stop_reason").value("end_turn"));
  }

  @Test
  void denyPathReturns403WithViolations() throws Exception {
    GovernanceViolation v =
        new GovernanceViolation(
            "MANIFEST_NOT_FOUND",
            "Manifest 'unknown' is not registered",
            "tool_manifest_id");
    when(kernel.evaluate(any())).thenReturn(new GovernanceDecision.Deny(List.of(v)));
    when(registry.snapshot()).thenReturn(java.util.Map.of());
    when(auditService.recordDeny(any(), any(), any(), any(), anyLong()))
        .thenReturn(fakeReceipt("DENY", "req-2"));

    String body =
        """
        {"agent_id":"summarizer-v1","tool_manifest_id":"unknown","prompt":"hello"}
        """;

    mockMvc
        .perform(post("/v1/governed-call").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.decision").value("DENY"))
        .andExpect(jsonPath("$.violations[0].code").value("MANIFEST_NOT_FOUND"))
        .andExpect(jsonPath("$.violations[0].field_path").value("tool_manifest_id"));

    verify(llmClient, never()).complete(any(), any(), any());
  }

  @Test
  void schemaViolationReturns400AndWritesNoReceipt() throws Exception {
    String missingPrompt =
        """
        {"agent_id":"summarizer-v1","tool_manifest_id":"readonly-summarizer"}
        """;
    mockMvc
        .perform(
            post("/v1/governed-call")
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingPrompt))
        .andExpect(status().isBadRequest());

    String badAgent =
        """
        {"agent_id":"BAD AGENT","tool_manifest_id":"readonly-summarizer","prompt":"hi"}
        """;
    mockMvc
        .perform(
            post("/v1/governed-call").contentType(MediaType.APPLICATION_JSON).content(badAgent))
        .andExpect(status().isBadRequest());

    verify(kernel, never()).evaluate(any());
    verify(auditService, never()).recordAllow(any(), any(), any(), any(), anyLong());
    verify(auditService, never()).recordDeny(any(), any(), any(), any(), anyLong());
  }

  @Test
  void getReceiptReturns404WhenUnknown() throws Exception {
    when(auditService.findById("nope")).thenReturn(Optional.empty());

    mockMvc.perform(get("/v1/receipts/nope")).andExpect(status().isNotFound());
  }

  @Test
  void getReceiptReturns200WhenFound() throws Exception {
    when(auditService.findById("rec-1")).thenReturn(Optional.of(fakeReceipt("ALLOW", "req-1")));

    mockMvc
        .perform(get("/v1/receipts/rec-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receipt_id").value("rec-1"))
        .andExpect(jsonPath("$.decision").value("ALLOW"))
        .andExpect(jsonPath("$.receipt_hash").value("sha256:rh"));
  }
}
