package net.m87.spinelite.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import java.time.Duration;
import net.m87.spinelite.config.AnthropicConfig;
import net.m87.spinelite.model.LlmResponse;
import org.springframework.stereotype.Component;

@Component
public class AnthropicLlmClient implements LlmClient {

  private static final long MAX_TOKENS = 1024L;

  private final AnthropicClient client;

  public AnthropicLlmClient(AnthropicConfig config) {
    if (config.apiKey() == null || config.apiKey().isBlank()) {
      throw new IllegalStateException(
          "ANTHROPIC_API_KEY is required at boot but was not set. "
              + "Export ANTHROPIC_API_KEY before starting the service.");
    }
    this.client =
        AnthropicOkHttpClient.builder()
            .apiKey(config.apiKey())
            .timeout(Duration.ofMillis(config.timeoutMs()))
            .build();
  }

  @Override
  public LlmResponse complete(String model, String systemPrompt, String userPrompt) {
    MessageCreateParams params =
        MessageCreateParams.builder()
            .maxTokens(MAX_TOKENS)
            .model(model)
            .system(systemPrompt)
            .addUserMessage(userPrompt)
            .build();

    Message message = client.messages().create(params);

    String content =
        message.content().stream()
            .filter(ContentBlock::isText)
            .map(b -> b.asText().text())
            .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

    String stopReason = message.stopReason().map(StopReason::asString).orElse(null);

    return new LlmResponse(content, message.model().asString(), stopReason);
  }
}
