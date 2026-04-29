package net.m87.spinelite.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.m87.spinelite.service.AuditReceiptService;
import net.m87.spinelite.service.GovernanceKernel;
import net.m87.spinelite.service.LlmClient;
import net.m87.spinelite.service.ManifestRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GovernedCallController.class)
class HealthzSmokeTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private ManifestRegistry registry;
  @MockBean private GovernanceKernel kernel;
  @MockBean private LlmClient llmClient;
  @MockBean private AuditReceiptService auditService;

  @Test
  void healthzReturnsOk() throws Exception {
    when(registry.size()).thenReturn(2);

    mockMvc
        .perform(get("/healthz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.manifests_loaded").value(2));
  }
}
