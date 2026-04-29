package net.m87.spinelite.adversarial;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.m87.spinelite.SpineLiteApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/**
 * Adversarial case 9: with no ANTHROPIC_API_KEY supplied, Spring context startup must fail fast and
 * the failure message must point the operator at the missing variable.
 */
class AdversarialMissingApiKeyTest {

  @Test
  void contextStartupFailsWithoutApiKey() {
    SpringApplication app = new SpringApplication(SpineLiteApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);

    // The expected boot failure produces an "Application run failed" stack trace at ERROR
    // level from SpringApplication. Suppress it for this test only via a Spring Boot logging
    // property — passing it as a CLI arg means LoggingApplicationListener applies it during
    // the same run we are exercising. The assertion below proves the failure happened; the
    // noise just clutters CI output.
    assertThatThrownBy(
            () ->
                app.run(
                    "--anthropic.api-key=",
                    "--server.port=0",
                    "--logging.level.org.springframework.boot.SpringApplication=OFF"))
        .rootCause()
        .hasMessageContaining("ANTHROPIC_API_KEY");
  }
}
