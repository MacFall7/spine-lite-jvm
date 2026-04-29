package net.m87.spinelite.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditReceipt(
    String receiptId,
    String requestId,
    Instant timestampUtc,
    String agentId,
    String manifestId,
    String manifestHash,
    String decision,
    List<String> violationCodes,
    String promptHash,
    String responseHash,
    String model,
    String receiptHash) {}
