package net.m87.spinelite.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import net.m87.spinelite.model.AuditReceipt;
import net.m87.spinelite.model.EffectClass;
import net.m87.spinelite.model.GovernanceViolation;
import net.m87.spinelite.model.GovernedCallRequest;
import net.m87.spinelite.model.LlmResponse;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.model.ToolManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuditReceiptServiceTest {

  private AuditReceiptService service;
  private ListAppender<ILoggingEvent> appender;
  private Logger captured;

  @BeforeEach
  void setUp() {
    service = new AuditReceiptService();
    captured = (Logger) LoggerFactory.getLogger(AuditReceiptService.class);
    appender = new ListAppender<>();
    appender.start();
    captured.addAppender(appender);
    captured.setLevel(Level.INFO);
  }

  @AfterEach
  void tearDown() {
    captured.detachAppender(appender);
  }

  private static ToolManifest manifest(boolean logPrompt) {
    return new ToolManifest(
        "m1",
        "1.0.0",
        List.of("agent-1"),
        EffectClass.READ_ONLY,
        List.of("claude-sonnet-4-6"),
        16000,
        "system prompt",
        new ToolManifest.RedactionRules(true, logPrompt));
  }

  private static GovernedCallRequest request(String prompt) {
    return new GovernedCallRequest("agent-1", "m1", prompt, null);
  }

  @Test
  void allowReceiptStoresAndIsRetrievable() {
    LoadedManifest loaded = new LoadedManifest(manifest(false), "sha256:m");
    AuditReceipt receipt =
        service.recordAllow(
            request("hello"),
            "req-1",
            loaded,
            new LlmResponse("hi back", "claude-sonnet-4-6", "end_turn"),
            42);

    assertThat(receipt.decision()).isEqualTo("ALLOW");
    assertThat(receipt.responseHash()).startsWith("sha256:");
    assertThat(receipt.model()).isEqualTo("claude-sonnet-4-6");
    assertThat(receipt.receiptHash()).startsWith("sha256:");
    assertThat(service.findById(receipt.receiptId())).contains(receipt);
  }

  @Test
  void denyReceiptHasNullResponseHashAndNullModel() {
    LoadedManifest loaded = new LoadedManifest(manifest(false), "sha256:m");
    AuditReceipt receipt =
        service.recordDeny(
            request("hi"),
            "req-2",
            Optional.of(loaded),
            List.of(new GovernanceViolation("EFFECT_CLASS_FORBIDDEN", "no", "effect_class")),
            10);

    assertThat(receipt.decision()).isEqualTo("DENY");
    assertThat(receipt.responseHash()).isNull();
    assertThat(receipt.model()).isNull();
    assertThat(receipt.violationCodes()).containsExactly("EFFECT_CLASS_FORBIDDEN");
    assertThat(receipt.receiptHash()).startsWith("sha256:");
  }

  @Test
  void denyReceiptForUnknownManifestHasNullManifestHash() {
    AuditReceipt receipt =
        service.recordDeny(
            request("hi"),
            "req-3",
            Optional.empty(),
            List.of(new GovernanceViolation("MANIFEST_NOT_FOUND", "no", "tool_manifest_id")),
            5);

    assertThat(receipt.manifestHash()).isNull();
  }

  @Test
  void receiptHashIsDeterministicForIdenticalInputs() {
    AuditReceipt template =
        new AuditReceipt(
            "rid", "qid", java.time.Instant.parse("2026-04-29T14:23:01.456Z"),
            "agent-1", "m1", "sha256:m", "ALLOW",
            List.of(), "sha256:p", "sha256:r", "claude-sonnet-4-6", null);

    String h1 = service.computeReceiptHash(template);
    for (int i = 0; i < 1000; i++) {
      assertThat(service.computeReceiptHash(template)).isEqualTo(h1);
    }
  }

  @Test
  void receiptHashChangesIfAnyFieldChanges() {
    AuditReceipt base =
        new AuditReceipt(
            "rid", "qid", java.time.Instant.parse("2026-04-29T14:23:01.456Z"),
            "agent-1", "m1", "sha256:m", "ALLOW",
            List.of(), "sha256:p", "sha256:r", "claude-sonnet-4-6", null);
    String baseHash = service.computeReceiptHash(base);

    AuditReceipt mutatedAgent = withAgent(base, "agent-2");
    AuditReceipt mutatedDecision = withDecision(base, "DENY");
    AuditReceipt mutatedPrompt = withPrompt(base, "sha256:different");
    AuditReceipt mutatedTimestamp = withTimestamp(base, base.timestampUtc().plusSeconds(1));

    assertThat(service.computeReceiptHash(mutatedAgent)).isNotEqualTo(baseHash);
    assertThat(service.computeReceiptHash(mutatedDecision)).isNotEqualTo(baseHash);
    assertThat(service.computeReceiptHash(mutatedPrompt)).isNotEqualTo(baseHash);
    assertThat(service.computeReceiptHash(mutatedTimestamp)).isNotEqualTo(baseHash);
  }

  @Test
  void rawPromptIsNeverWrittenToLogsWhenLogPromptFalse() {
    String secretPrompt = "PATIENT_SSN_123-45-6789";
    LoadedManifest loaded = new LoadedManifest(manifest(false), "sha256:m");
    service.recordAllow(
        request(secretPrompt),
        "req-1",
        loaded,
        new LlmResponse("ok", "claude-sonnet-4-6", "end_turn"),
        1);

    for (ILoggingEvent event : appender.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(secretPrompt);
      for (Object arg : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
        assertThat(String.valueOf(arg)).doesNotContain(secretPrompt);
      }
    }
  }

  @Test
  void rawResponseIsNeverWrittenToLogs() {
    String secretResponse = "MODEL_LEAKED_KEY_xyz";
    LoadedManifest loaded = new LoadedManifest(manifest(true), "sha256:m");
    service.recordAllow(
        request("hi"),
        "req-1",
        loaded,
        new LlmResponse(secretResponse, "claude-sonnet-4-6", "end_turn"),
        1);

    for (ILoggingEvent event : appender.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(secretResponse);
      for (Object arg : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
        assertThat(String.valueOf(arg)).doesNotContain(secretResponse);
      }
    }
  }

  @Test
  void promptPrefixIsLoggedWhenManifestOptsIn() {
    String prompt = "x".repeat(500);
    LoadedManifest loaded = new LoadedManifest(manifest(true), "sha256:m");
    service.recordAllow(
        request(prompt),
        "req-1",
        loaded,
        new LlmResponse("ok", "claude-sonnet-4-6", "end_turn"),
        1);

    boolean foundPrefixArg =
        appender.list.stream()
            .flatMap(
                e ->
                    e.getArgumentArray() == null
                        ? java.util.stream.Stream.empty()
                        : java.util.Arrays.stream(e.getArgumentArray()))
            .anyMatch(a -> String.valueOf(a).contains("prompt_prefix"));
    assertThat(foundPrefixArg).isTrue();
  }

  private static AuditReceipt withAgent(AuditReceipt r, String agent) {
    return new AuditReceipt(
        r.receiptId(), r.requestId(), r.timestampUtc(), agent, r.manifestId(), r.manifestHash(),
        r.decision(), r.violationCodes(), r.promptHash(), r.responseHash(), r.model(),
        r.receiptHash());
  }

  private static AuditReceipt withDecision(AuditReceipt r, String decision) {
    return new AuditReceipt(
        r.receiptId(), r.requestId(), r.timestampUtc(), r.agentId(), r.manifestId(),
        r.manifestHash(), decision, r.violationCodes(), r.promptHash(), r.responseHash(),
        r.model(), r.receiptHash());
  }

  private static AuditReceipt withPrompt(AuditReceipt r, String promptHash) {
    return new AuditReceipt(
        r.receiptId(), r.requestId(), r.timestampUtc(), r.agentId(), r.manifestId(),
        r.manifestHash(), r.decision(), r.violationCodes(), promptHash, r.responseHash(),
        r.model(), r.receiptHash());
  }

  private static AuditReceipt withTimestamp(AuditReceipt r, java.time.Instant ts) {
    return new AuditReceipt(
        r.receiptId(), r.requestId(), ts, r.agentId(), r.manifestId(), r.manifestHash(),
        r.decision(), r.violationCodes(), r.promptHash(), r.responseHash(), r.model(),
        r.receiptHash());
  }
}
