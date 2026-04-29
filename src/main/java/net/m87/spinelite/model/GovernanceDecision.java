package net.m87.spinelite.model;

import java.util.List;

public sealed interface GovernanceDecision
    permits GovernanceDecision.Allow, GovernanceDecision.Deny {

  record Allow(LoadedManifest loadedManifest, String resolvedModel) implements GovernanceDecision {}

  record Deny(List<GovernanceViolation> violations) implements GovernanceDecision {}
}
