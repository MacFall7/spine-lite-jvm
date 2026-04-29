package net.m87.spinelite.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.m87.spinelite.model.AuditReceipt;
import net.m87.spinelite.model.GovernanceViolation;
import net.m87.spinelite.model.GovernedCallRequest;
import net.m87.spinelite.model.LlmResponse;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.model.ToolManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuditReceiptService {

  private static final Logger log = LoggerFactory.getLogger(AuditReceiptService.class);
  private static final int LOG_PROMPT_PREFIX_CHARS = 200;

  private final ConcurrentMap<String, AuditReceipt> store = new ConcurrentHashMap<>();
  private final ObjectMapper canonicalMapper;

  public AuditReceiptService() {
    this.canonicalMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
  }

  public AuditReceipt recordAllow(
      GovernedCallRequest req,
      String requestId,
      LoadedManifest loaded,
      LlmResponse llmResponse,
      long latencyMs) {

    AuditReceipt receipt =
        finalize(
            new AuditReceipt(
                UUID.randomUUID().toString(),
                requestId,
                Instant.now(),
                req.agentId(),
                loaded.manifest().manifestId(),
                loaded.loadTimeHash(),
                "ALLOW",
                List.of(),
                hashUtf8(req.prompt()),
                hashUtf8(llmResponse.content()),
                llmResponse.model(),
                null));

    store.put(receipt.receiptId(), receipt);
    emitLog(receipt, loaded.manifest(), req.prompt(), latencyMs);
    return receipt;
  }

  public AuditReceipt recordDeny(
      GovernedCallRequest req,
      String requestId,
      Optional<LoadedManifest> loaded,
      List<GovernanceViolation> violations,
      long latencyMs) {

    AuditReceipt receipt =
        finalize(
            new AuditReceipt(
                UUID.randomUUID().toString(),
                requestId,
                Instant.now(),
                req.agentId(),
                req.toolManifestId(),
                loaded.map(LoadedManifest::loadTimeHash).orElse(null),
                "DENY",
                violations.stream().map(GovernanceViolation::code).toList(),
                hashUtf8(req.prompt()),
                null,
                null,
                null));

    store.put(receipt.receiptId(), receipt);
    emitLog(receipt, loaded.map(LoadedManifest::manifest).orElse(null), req.prompt(), latencyMs);
    return receipt;
  }

  public Optional<AuditReceipt> findById(String receiptId) {
    return Optional.ofNullable(store.get(receiptId));
  }

  /**
   * Recomputes the canonical receipt hash and confirms it matches the value stored in {@code
   * receipt_hash}. Used by the integration test and by external verifiers.
   */
  public boolean verify(AuditReceipt receipt) {
    AuditReceipt withoutHash =
        new AuditReceipt(
            receipt.receiptId(),
            receipt.requestId(),
            receipt.timestampUtc(),
            receipt.agentId(),
            receipt.manifestId(),
            receipt.manifestHash(),
            receipt.decision(),
            receipt.violationCodes(),
            receipt.promptHash(),
            receipt.responseHash(),
            receipt.model(),
            null);
    return computeReceiptHash(withoutHash).equals(receipt.receiptHash());
  }

  private AuditReceipt finalize(AuditReceipt unsealed) {
    String receiptHash = computeReceiptHash(unsealed);
    return new AuditReceipt(
        unsealed.receiptId(),
        unsealed.requestId(),
        unsealed.timestampUtc(),
        unsealed.agentId(),
        unsealed.manifestId(),
        unsealed.manifestHash(),
        unsealed.decision(),
        unsealed.violationCodes(),
        unsealed.promptHash(),
        unsealed.responseHash(),
        unsealed.model(),
        receiptHash);
  }

  String computeReceiptHash(AuditReceipt withoutHash) {
    try {
      byte[] canonical = canonicalMapper.writeValueAsBytes(withoutHash);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(canonical));
    } catch (IOException e) {
      throw new IllegalStateException("Canonical serialization failed", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String hashUtf8(String s) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void emitLog(
      AuditReceipt receipt, ToolManifest manifest, String rawPrompt, long latencyMs) {
    if (manifest != null
        && manifest.redactionRules() != null
        && manifest.redactionRules().logPrompt()) {
      String prefix =
          rawPrompt.length() > LOG_PROMPT_PREFIX_CHARS
              ? rawPrompt.substring(0, LOG_PROMPT_PREFIX_CHARS)
              : rawPrompt;
      log.info(
          "audit_receipt",
          kv("receipt_id", receipt.receiptId()),
          kv("request_id", receipt.requestId()),
          kv("agent_id", receipt.agentId()),
          kv("manifest_id", receipt.manifestId()),
          kv("decision", receipt.decision()),
          kv("violation_codes", receipt.violationCodes()),
          kv("model", receipt.model()),
          kv("latency_ms", latencyMs),
          kv("prompt_prefix", prefix));
    } else {
      log.info(
          "audit_receipt",
          kv("receipt_id", receipt.receiptId()),
          kv("request_id", receipt.requestId()),
          kv("agent_id", receipt.agentId()),
          kv("manifest_id", receipt.manifestId()),
          kv("decision", receipt.decision()),
          kv("violation_codes", receipt.violationCodes()),
          kv("model", receipt.model()),
          kv("latency_ms", latencyMs));
    }
  }
}
