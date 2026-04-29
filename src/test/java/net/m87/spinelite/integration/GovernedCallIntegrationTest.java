package net.m87.spinelite.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import net.m87.spinelite.model.AuditReceipt;
import net.m87.spinelite.model.GovernedCallResponse;
import net.m87.spinelite.model.LlmResponse;
import net.m87.spinelite.service.AuditReceiptService;
import net.m87.spinelite.service.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "anthropic.api-key=test-key")
class GovernedCallIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate http;
  @Autowired private AuditReceiptService auditService;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private LlmClient llmClient;

  @Test
  void endToEndAllowAndReceiptVerifies() {
    when(llmClient.complete(any(), any(), any()))
        .thenReturn(new LlmResponse("summary text", "claude-sonnet-4-6", "end_turn"));

    String body =
        """
        {"agent_id":"summarizer-v1","tool_manifest_id":"readonly-summarizer","prompt":"summarize this"}
        """;

    ResponseEntity<GovernedCallResponse> post =
        http.exchange(
            RequestEntity.post(URI.create(url("/v1/governed-call")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body),
            GovernedCallResponse.class);

    assertThat(post.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post.getBody().decision()).isEqualTo("ALLOW");
    assertThat(post.getBody().receiptId()).isNotBlank();
    assertThat(post.getBody().response().content()).isEqualTo("summary text");

    String receiptId = post.getBody().receiptId();
    ResponseEntity<AuditReceipt> get =
        http.getForEntity(url("/v1/receipts/" + receiptId), AuditReceipt.class);

    assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
    AuditReceipt receipt = get.getBody();
    assertThat(receipt.receiptId()).isEqualTo(receiptId);
    assertThat(receipt.decision()).isEqualTo("ALLOW");
    assertThat(receipt.manifestHash()).startsWith("sha256:");
    assertThat(receipt.promptHash()).startsWith("sha256:");
    assertThat(receipt.responseHash()).startsWith("sha256:");
    assertThat(receipt.receiptHash()).startsWith("sha256:");

    assertThat(auditService.verify(receipt))
        .as("receipt_hash must verify against the canonical content")
        .isTrue();
  }

  @Test
  void endToEndDenyForUnknownManifest() {
    String body =
        """
        {"agent_id":"summarizer-v1","tool_manifest_id":"no-such-manifest","prompt":"hi"}
        """;
    ResponseEntity<GovernedCallResponse> post =
        http.exchange(
            RequestEntity.post(URI.create(url("/v1/governed-call")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body),
            GovernedCallResponse.class);

    assertThat(post.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post.getBody().decision()).isEqualTo("DENY");
    assertThat(post.getBody().violations().get(0).code()).isEqualTo("MANIFEST_NOT_FOUND");
    assertThat(post.getBody().receiptId()).isNotBlank();

    ResponseEntity<AuditReceipt> get =
        http.getForEntity(url("/v1/receipts/" + post.getBody().receiptId()), AuditReceipt.class);
    assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get.getBody().decision()).isEqualTo("DENY");
    assertThat(auditService.verify(get.getBody())).isTrue();
  }

  @Test
  void healthzReportsRealManifestCount() {
    ResponseEntity<String> resp = http.getForEntity(url("/healthz"), String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).contains("\"manifests_loaded\":2").contains("\"status\":\"ok\"");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
