package net.m87.spinelite.controller;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.m87.spinelite.model.AuditReceipt;
import net.m87.spinelite.model.GovernanceDecision;
import net.m87.spinelite.model.GovernedCallRequest;
import net.m87.spinelite.model.GovernedCallResponse;
import net.m87.spinelite.model.LlmResponse;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.service.AuditReceiptService;
import net.m87.spinelite.service.GovernanceKernel;
import net.m87.spinelite.service.LlmClient;
import net.m87.spinelite.service.ManifestRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GovernedCallController {

  private final ManifestRegistry registry;
  private final GovernanceKernel kernel;
  private final LlmClient llmClient;
  private final AuditReceiptService auditService;

  public GovernedCallController(
      ManifestRegistry registry,
      GovernanceKernel kernel,
      LlmClient llmClient,
      AuditReceiptService auditService) {
    this.registry = registry;
    this.kernel = kernel;
    this.llmClient = llmClient;
    this.auditService = auditService;
  }

  @GetMapping("/healthz")
  public Map<String, Object> healthz() {
    return Map.of("status", "ok", "manifests_loaded", registry.size());
  }

  @PostMapping("/v1/governed-call")
  public ResponseEntity<GovernedCallResponse> governedCall(
      @Valid @RequestBody GovernedCallRequest request) {

    String requestId = resolveRequestId(request);
    long start = System.currentTimeMillis();
    GovernanceDecision decision = kernel.evaluate(request);

    if (decision instanceof GovernanceDecision.Allow allow) {
      LoadedManifest loaded = allow.loadedManifest();
      LlmResponse llmResponse =
          llmClient.complete(
              allow.resolvedModel(), loaded.manifest().systemPrompt(), request.prompt());
      long latency = System.currentTimeMillis() - start;
      AuditReceipt receipt =
          auditService.recordAllow(request, requestId, loaded, llmResponse, latency);
      return ResponseEntity.ok(
          GovernedCallResponse.allow(requestId, receipt.receiptId(), llmResponse));
    }

    GovernanceDecision.Deny deny = (GovernanceDecision.Deny) decision;
    Optional<LoadedManifest> manifestForReceipt =
        Optional.ofNullable(registry.snapshot().get(request.toolManifestId()));
    long latency = System.currentTimeMillis() - start;
    AuditReceipt receipt =
        auditService.recordDeny(request, requestId, manifestForReceipt, deny.violations(), latency);
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(GovernedCallResponse.deny(requestId, receipt.receiptId(), deny.violations()));
  }

  @GetMapping("/v1/receipts/{receiptId}")
  public ResponseEntity<AuditReceipt> getReceipt(@PathVariable String receiptId) {
    return auditService
        .findById(receiptId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static String resolveRequestId(GovernedCallRequest request) {
    if (request.metadata() != null
        && request.metadata().requestId() != null
        && !request.metadata().requestId().isBlank()) {
      return request.metadata().requestId();
    }
    return UUID.randomUUID().toString();
  }
}
