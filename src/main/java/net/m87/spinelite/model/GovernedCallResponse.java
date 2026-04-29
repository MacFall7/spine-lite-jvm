package net.m87.spinelite.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GovernedCallResponse(
    String decision,
    String requestId,
    String receiptId,
    LlmResponse response,
    List<GovernanceViolation> violations) {

  public static GovernedCallResponse allow(
      String requestId, String receiptId, LlmResponse response) {
    return new GovernedCallResponse("ALLOW", requestId, receiptId, response, null);
  }

  public static GovernedCallResponse deny(
      String requestId, String receiptId, List<GovernanceViolation> violations) {
    return new GovernedCallResponse("DENY", requestId, receiptId, null, violations);
  }
}
