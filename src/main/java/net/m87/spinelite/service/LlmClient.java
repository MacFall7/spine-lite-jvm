package net.m87.spinelite.service;

import net.m87.spinelite.model.LlmResponse;

public interface LlmClient {

  LlmResponse complete(String model, String systemPrompt, String userPrompt);
}
