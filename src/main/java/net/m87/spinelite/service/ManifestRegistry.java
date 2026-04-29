package net.m87.spinelite.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import net.m87.spinelite.config.ManifestConfig;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.model.ToolManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class ManifestRegistry {

  private static final Logger log = LoggerFactory.getLogger(ManifestRegistry.class);

  private final ManifestConfig config;
  private final ObjectMapper jsonMapper;
  private final ObjectMapper canonicalMapper;
  private final ResourcePatternResolver resolver;
  private final Map<String, LoadedManifest> byId = new HashMap<>();

  public ManifestRegistry(ManifestConfig config) {
    this.config = config;
    this.jsonMapper =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    this.canonicalMapper =
        new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    this.resolver = new PathMatchingResourcePatternResolver();
  }

  @PostConstruct
  void load() {
    String pattern = config.classpathPattern();
    Resource[] resources;
    try {
      resources = resolver.getResources(pattern);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to scan manifest pattern: " + pattern, e);
    }

    for (Resource r : resources) {
      ToolManifest manifest;
      try {
        manifest = jsonMapper.readValue(r.getInputStream(), ToolManifest.class);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Failed to parse manifest file: " + r.getDescription(), e);
      }
      validate(manifest, r.getDescription());
      String hash = computeHash(manifest);
      if (byId.containsKey(manifest.manifestId())) {
        throw new IllegalStateException(
            "Duplicate manifest_id at boot: " + manifest.manifestId());
      }
      byId.put(manifest.manifestId(), new LoadedManifest(manifest, hash));
      log.info(
          "Loaded manifest manifest_id={} effect_class={} hash={}",
          manifest.manifestId(),
          manifest.effectClass(),
          hash);
    }
    log.info("ManifestRegistry boot complete count={}", byId.size());
  }

  public Optional<LoadedManifest> resolve(String agentId, String manifestId) {
    LoadedManifest loaded = byId.get(manifestId);
    if (loaded == null) {
      return Optional.empty();
    }
    if (!loaded.manifest().boundAgentIds().contains(agentId)) {
      return Optional.empty();
    }
    return Optional.of(loaded);
  }

  public boolean isRegistered(String manifestId) {
    return byId.containsKey(manifestId);
  }

  public int size() {
    return byId.size();
  }

  public Map<String, LoadedManifest> snapshot() {
    return Collections.unmodifiableMap(byId);
  }

  public String computeHash(ToolManifest manifest) {
    try {
      byte[] canonical = canonicalMapper.writeValueAsBytes(manifest);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonical);
      return "sha256:" + HexFormat.of().formatHex(hash);
    } catch (IOException e) {
      throw new IllegalStateException("Canonical serialization failed", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void validate(ToolManifest m, String source) {
    require(m.manifestId() != null && !m.manifestId().isBlank(), "manifest_id", source);
    require(
        m.manifestVersion() != null && !m.manifestVersion().isBlank(),
        "manifest_version",
        source);
    require(
        m.boundAgentIds() != null && !m.boundAgentIds().isEmpty(), "bound_agent_ids", source);
    require(m.effectClass() != null, "effect_class", source);
    require(
        m.allowedModels() != null && !m.allowedModels().isEmpty(), "allowed_models", source);
    require(m.maxPromptChars() > 0, "max_prompt_chars", source);
    require(m.systemPrompt() != null && !m.systemPrompt().isBlank(), "system_prompt", source);
    require(m.redactionRules() != null, "redaction_rules", source);
  }

  private static void require(boolean condition, String field, String source) {
    if (!condition) {
      throw new IllegalStateException(
          "Manifest field '" + field + "' is missing or invalid in: " + source);
    }
  }
}
