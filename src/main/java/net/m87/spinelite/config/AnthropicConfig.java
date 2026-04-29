package net.m87.spinelite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anthropic")
public record AnthropicConfig(String apiKey, String defaultModel, long timeoutMs) {}
