package net.m87.spinelite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spine-lite.manifests")
public record ManifestConfig(String classpathPattern) {}
