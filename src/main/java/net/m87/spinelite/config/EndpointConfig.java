package net.m87.spinelite.config;

import java.util.List;
import java.util.Set;
import net.m87.spinelite.model.EffectClass;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spine-lite.endpoint")
public record EndpointConfig(List<EffectClass> forbiddenEffectClasses) {

  public Set<EffectClass> forbiddenSet() {
    return forbiddenEffectClasses == null ? Set.of() : Set.copyOf(forbiddenEffectClasses);
  }
}
