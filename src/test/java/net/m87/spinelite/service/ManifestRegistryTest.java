package net.m87.spinelite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.m87.spinelite.config.ManifestConfig;
import net.m87.spinelite.model.EffectClass;
import net.m87.spinelite.model.LoadedManifest;
import net.m87.spinelite.model.ToolManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestRegistryTest {

  @Test
  void loadsValidManifestsFromClasspath() {
    ManifestRegistry registry = new ManifestRegistry(defaultConfig());
    registry.load();

    assertThat(registry.size()).isEqualTo(2);
    assertThat(registry.isRegistered("readonly-summarizer")).isTrue();
    assertThat(registry.isRegistered("write-bounded-classifier")).isTrue();
  }

  @Test
  void resolveReturnsManifestWhenAgentBound() {
    ManifestRegistry registry = new ManifestRegistry(defaultConfig());
    registry.load();

    LoadedManifest loaded = registry.resolve("summarizer-v1", "readonly-summarizer").orElseThrow();
    assertThat(loaded.manifest().effectClass()).isEqualTo(EffectClass.READ_ONLY);
    assertThat(loaded.loadTimeHash()).startsWith("sha256:");
    assertThat(loaded.loadTimeHash()).hasSize("sha256:".length() + 64);
  }

  @Test
  void resolveReturnsEmptyWhenAgentNotBound() {
    ManifestRegistry registry = new ManifestRegistry(defaultConfig());
    registry.load();

    assertThat(registry.resolve("classifier-v1", "readonly-summarizer")).isEmpty();
  }

  @Test
  void resolveReturnsEmptyWhenManifestUnknown() {
    ManifestRegistry registry = new ManifestRegistry(defaultConfig());
    registry.load();

    assertThat(registry.resolve("summarizer-v1", "no-such-manifest")).isEmpty();
  }

  @Test
  void hashIsDeterministicAcrossLoadsForSameInputs() {
    ManifestRegistry r1 = new ManifestRegistry(defaultConfig());
    r1.load();
    ManifestRegistry r2 = new ManifestRegistry(defaultConfig());
    r2.load();

    String h1 = r1.resolve("summarizer-v1", "readonly-summarizer").orElseThrow().loadTimeHash();
    String h2 = r2.resolve("summarizer-v1", "readonly-summarizer").orElseThrow().loadTimeHash();
    assertThat(h1).isEqualTo(h2);
  }

  @Test
  void hashChangesWhenAnyFieldChanges() {
    ManifestRegistry registry = new ManifestRegistry(defaultConfig());
    registry.load();

    ToolManifest original =
        registry.resolve("summarizer-v1", "readonly-summarizer").orElseThrow().manifest();
    ToolManifest mutated =
        new ToolManifest(
            original.manifestId(),
            original.manifestVersion(),
            original.boundAgentIds(),
            EffectClass.WRITE_PRIVILEGED,
            original.allowedModels(),
            original.maxPromptChars(),
            original.systemPrompt(),
            original.redactionRules());

    assertThat(registry.computeHash(mutated)).isNotEqualTo(registry.computeHash(original));
  }

  @Test
  void rejectsMalformedJson(@TempDir Path tempDir) throws IOException {
    Path bad = tempDir.resolve("bad.json");
    Files.writeString(bad, "{ this is not valid json }");

    ManifestRegistry registry =
        new ManifestRegistry(new ManifestConfig("file:" + tempDir + "/*.json"));

    assertThatThrownBy(registry::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse manifest");
  }

  @Test
  void rejectsManifestMissingRequiredField(@TempDir Path tempDir) throws IOException {
    Path missing = tempDir.resolve("missing-field.json");
    Files.writeString(
        missing,
        """
        {
          "manifest_id": "broken",
          "manifest_version": "1.0.0",
          "bound_agent_ids": ["x"],
          "effect_class": "READ_ONLY",
          "allowed_models": ["claude-sonnet-4-6"],
          "max_prompt_chars": 100,
          "redaction_rules": {"strip_pii_from_logs": true, "log_prompt": false}
        }
        """);

    ManifestRegistry registry =
        new ManifestRegistry(new ManifestConfig("file:" + tempDir + "/*.json"));

    assertThatThrownBy(registry::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("system_prompt");
  }

  @Test
  void rejectsDuplicateManifestId(@TempDir Path tempDir) throws IOException {
    String body =
        """
        {
          "manifest_id": "dup",
          "manifest_version": "1.0.0",
          "bound_agent_ids": ["a"],
          "effect_class": "READ_ONLY",
          "allowed_models": ["claude-sonnet-4-6"],
          "max_prompt_chars": 100,
          "system_prompt": "x",
          "redaction_rules": {"strip_pii_from_logs": true, "log_prompt": false}
        }
        """;
    Files.writeString(tempDir.resolve("a.json"), body);
    Files.writeString(tempDir.resolve("b.json"), body);

    ManifestRegistry registry =
        new ManifestRegistry(new ManifestConfig("file:" + tempDir + "/*.json"));

    assertThatThrownBy(registry::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate manifest_id");
  }

  @Test
  void rejectsManifestWithEmptyBoundAgentIds(@TempDir Path tempDir) throws IOException {
    Files.writeString(
        tempDir.resolve("empty-agents.json"),
        """
        {
          "manifest_id": "empty",
          "manifest_version": "1.0.0",
          "bound_agent_ids": [],
          "effect_class": "READ_ONLY",
          "allowed_models": ["claude-sonnet-4-6"],
          "max_prompt_chars": 100,
          "system_prompt": "x",
          "redaction_rules": {"strip_pii_from_logs": true, "log_prompt": false}
        }
        """);

    ManifestRegistry registry =
        new ManifestRegistry(new ManifestConfig("file:" + tempDir + "/*.json"));

    assertThatThrownBy(registry::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bound_agent_ids");
  }

  @SuppressWarnings("unused")
  private static List<String> listOf(String... s) {
    return List.of(s);
  }

  private static ManifestConfig defaultConfig() {
    return new ManifestConfig("classpath:/manifests/*.json");
  }
}
