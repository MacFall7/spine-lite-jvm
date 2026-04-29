package net.m87.spinelite.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import net.m87.spinelite.model.GovernedCallResponse;
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

/**
 * Adversarial case 4: a manifest declaring effect_class WRITE_PRIVILEGED is loaded into the
 * registry. Calls to it must be denied with EFFECT_CLASS_FORBIDDEN regardless of agent
 * binding or prompt content.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "spine-lite.manifests.classpath-pattern=classpath:/adversarial-manifests/*.json",
      "anthropic.api-key=test-key"
    })
class AdversarialWritePrivilegedTest {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate http;
  @MockBean private LlmClient llmClient;

  @Test
  void writePrivilegedManifestIsRejectedByEndpointConfig() {
    String body =
        """
        {"agent_id":"adversarial-agent","tool_manifest_id":"write-privileged-test","prompt":"hi"}
        """;
    ResponseEntity<GovernedCallResponse> resp =
        http.exchange(
            RequestEntity.post(URI.create(url("/v1/governed-call")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body),
            GovernedCallResponse.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(resp.getBody().decision()).isEqualTo("DENY");
    assertThat(resp.getBody().violations())
        .singleElement()
        .extracting("code")
        .isEqualTo("EFFECT_CLASS_FORBIDDEN");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
