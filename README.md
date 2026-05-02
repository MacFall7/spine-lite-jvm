# Spine Lite JVM

Governed gateway for LLM calls. JVM port of the Spine Lite kernel pattern.

## Lineage

This service extends the M87 Studio **Spine Lite** governance architecture from
its original TypeScript / Edge runtime to the JVM. The kernel pattern is
unchanged: every LLM call passes through a fail-closed decision point that
resolves a signed tool manifest, checks an integrity hash, validates the
caller's binding and prompt size, and emits an auditable receipt regardless of
the outcome. This v1 demonstrates the same discipline in Spring Boot 3 + Java
21 and is intended to be reviewable in
under fifteen minutes.

## Architecture

```
┌────────────┐    POST /v1/governed-call    ┌──────────────────┐
│   Client   │ ───────────────────────────▶ │   Controller     │
└────────────┘                              └────────┬─────────┘
                                                     │
                                                     ▼
                                            ┌──────────────────┐
                                            │ ManifestRegistry │  loads manifests at boot
                                            └────────┬─────────┘
                                                     │ resolves
                                                     ▼
                                            ┌──────────────────┐
                                            │ GovernanceKernel │  ◀── fail-closed decision
                                            └────────┬─────────┘
                                                     │ ALLOW │ DENY
                                ┌────────────────────┴──────────────────┐
                                ▼                                       ▼
                       ┌─────────────────┐                    ┌──────────────────┐
                       │   LlmClient     │                    │ Violation 403    │
                       │ (Anthropic SDK) │                    └────────┬─────────┘
                       └────────┬────────┘                             │
                                │                                       │
                                ▼                                       │
                       ┌──────────────────┐                             │
                       │ AuditReceipt     │ ◀───────────────────────────┘
                       │ Service          │   receipts written for ALLOW and DENY
                       └────────┬─────────┘
                                │
                                ▼
                       ┌──────────────────┐
                       │ Response         │
                       │  + receipt_id    │
                       └──────────────────┘
```

The invariant is structural: the `LlmClient` is unreachable except through a
successful kernel decision, and there is no path that skips receipt
generation. Schema-level rejections (HTTP 400) never reach the kernel and
intentionally do not write a receipt — they did not enter the governed flow.

## Quickstart

```bash
git clone https://github.com/macfall7/spine-lite-jvm.git
cd spine-lite-jvm
export ANTHROPIC_API_KEY=sk-ant-...
./mvnw spring-boot:run
```

In another terminal:

```bash
# Health check
curl -s http://localhost:8080/healthz
# {"status":"ok","manifests_loaded":2}

# Allowed call against the readonly summarizer manifest
curl -s -X POST http://localhost:8080/v1/governed-call \
  -H 'Content-Type: application/json' \
  -d '{
        "agent_id": "summarizer-v1",
        "tool_manifest_id": "readonly-summarizer",
        "prompt": "Summarise: the patient reports persistent fatigue."
      }'

# Denied call (manifest not bound to this agent)
curl -s -X POST http://localhost:8080/v1/governed-call \
  -H 'Content-Type: application/json' \
  -d '{
        "agent_id": "classifier-v1",
        "tool_manifest_id": "readonly-summarizer",
        "prompt": "hello"
      }'

# Fetch a receipt
curl -s http://localhost:8080/v1/receipts/<receipt_id>
```

The full test suite (48 tests, including the 10 adversarial cases) runs with:

```bash
./mvnw verify
```

Tests do not require a live API key — every governance test mocks the
`LlmClient` bean.

## API contract

### `POST /v1/governed-call`

**Request:**

```json
{
  "agent_id": "summarizer-v1",
  "tool_manifest_id": "readonly-summarizer",
  "prompt": "Summarize the following clinical note: ...",
  "model": "claude-sonnet-4-6",
  "metadata": {
    "request_id": "optional-client-supplied-uuid",
    "trace_id": "optional"
  }
}
```

Validation (controller layer): `agent_id` and `tool_manifest_id` must match
`^[a-z0-9-]{3,64}$`; `prompt` must be 1–32 000 characters; `model` is
optional and (when present) must match `^[A-Za-z0-9._-]{1,128}$`; `metadata`
is optional and the server fills in `request_id` if absent.

If `model` is omitted, the kernel resolves it to the manifest's first entry
in `allowed_models`. If `model` is supplied but is not in the manifest's
`allowed_models`, the kernel denies with `MODEL_NOT_ALLOWED` — the manifest
is the authority on which models a given agent may invoke.

**200 ALLOW:**

```json
{
  "decision": "ALLOW",
  "request_id": "0f7f1f4e-7e0c-4b1f-9d4a-...",
  "receipt_id": "f1d9c0e8-...",
  "response": {
    "content": "model output text",
    "model": "claude-sonnet-4-6",
    "stop_reason": "end_turn"
  }
}
```

**403 DENY:**

```json
{
  "decision": "DENY",
  "request_id": "0f7f1f4e-...",
  "receipt_id": "0a1c-...",
  "violations": [
    {
      "code": "MANIFEST_NOT_FOUND",
      "message": "Manifest 'readonly-x' is not registered",
      "field_path": "tool_manifest_id"
    }
  ]
}
```

**400** is returned for schema-level violations (missing fields, regex
failures). No receipt is written for 400 responses — those never reached the
kernel.

Closed set of violation codes:

| Code                          | Meaning                                                       |
| ----------------------------- | ------------------------------------------------------------- |
| `MANIFEST_NOT_FOUND`          | No manifest with that id is registered                        |
| `MANIFEST_AGENT_MISMATCH`     | Manifest exists but is not bound to the calling agent         |
| `EFFECT_CLASS_FORBIDDEN`      | Manifest declares an effect class this endpoint forbids       |
| `PROMPT_SIZE_VIOLATION`       | Prompt is longer than the manifest's `max_prompt_chars`       |
| `MANIFEST_INTEGRITY_FAILURE`  | The current manifest hash does not match its load-time hash   |
| `MODEL_NOT_ALLOWED`           | Caller-supplied `model` is not in the manifest's `allowed_models` |

### `GET /v1/receipts/{receipt_id}`

Returns the stored audit receipt or 404.

### `GET /healthz`

Returns `{"status":"ok","manifests_loaded":N}`.

## Manifest schema

```json
{
  "manifest_id": "readonly-summarizer",
  "manifest_version": "1.0.0",
  "bound_agent_ids": ["summarizer-v1"],
  "effect_class": "READ_ONLY",
  "allowed_models": ["claude-sonnet-4-6", "claude-haiku-4-5-20251001"],
  "max_prompt_chars": 16000,
  "system_prompt": "You are a read-only clinical summarizer. You produce summaries; you never recommend, diagnose, or prescribe.",
  "redaction_rules": {
    "strip_pii_from_logs": true,
    "log_prompt": false
  }
}
```

`effect_class` is one of:

- `READ_ONLY` — no state mutation outside the response itself.
- `WRITE_BOUNDED` — mutations within a declared narrow scope.
- `WRITE_PRIVILEGED` — mutations across boundaries; **rejected by this v1
  endpoint by default** via `spine-lite.endpoint.forbidden-effect-classes`.

Manifests are scanned from `classpath:/manifests/*.json` at boot. Each
manifest is parsed, schema-validated, and assigned a SHA-256 hash over its
canonical JSON form (snake-case keys, alphabetically sorted). Failure to
parse or validate any file aborts startup. Two example manifests ship in the
repo:

- `src/main/resources/manifests/example-readonly-summarizer.json` —
  `READ_ONLY`, `summarizer-v1`, `max_prompt_chars=16000`.
- `src/main/resources/manifests/example-write-bounded-classifier.json` —
  `WRITE_BOUNDED`, `classifier-v1`, `max_prompt_chars=8000`.

## Receipt schema and verification

```json
{
  "receipt_id": "uuid",
  "request_id": "uuid",
  "timestamp_utc": "2026-04-29T14:23:01.456Z",
  "agent_id": "summarizer-v1",
  "manifest_id": "readonly-summarizer",
  "manifest_hash": "sha256:abc...",
  "decision": "ALLOW",
  "violation_codes": [],
  "prompt_hash": "sha256:...",
  "response_hash": "sha256:...",
  "model": "claude-sonnet-4-6",
  "receipt_hash": "sha256:..."
}
```

Hashing rules:

- All hashes are lowercase hex SHA-256, prefixed with `sha256:`.
- `prompt_hash` is `SHA-256(utf8 bytes of raw prompt)`.
- `response_hash` is `SHA-256(utf8 bytes of model content)`; `null` on DENY.
- `receipt_hash` is `SHA-256` over the canonical JSON of all *other* fields:
  snake-case keys, alphabetically sorted, no whitespace, ISO-8601 timestamps,
  and null-valued fields omitted. The receipt-hash field itself is set to
  `null` (and therefore omitted) during canonicalisation.

To verify a receipt externally:

1. Take the receipt JSON returned by `GET /v1/receipts/{id}`.
2. Set `receipt_hash` to `null` (or remove it).
3. Serialise the result with sorted keys, snake-case, no whitespace, and
   `JsonInclude.NON_NULL` (or equivalent — drop null fields).
4. Compute SHA-256 of the UTF-8 bytes.
5. Compare to the original `receipt_hash`. They must match.

The `AuditReceiptService.verify(AuditReceipt)` method implements this
algorithm in-process and is exercised by both the integration test and
adversarial case 10.

**Unicode note:** prompt and response hashes are computed over raw UTF-8
bytes with **no Unicode normalisation**. NFC and NFD encodings of the same
logical text therefore produce different hashes; this is intentional so that
audits reflect exactly what crossed the wire.

## Governance properties

The fail-closed invariants this service enforces:

1. **No path bypasses the kernel.** The only entry point that reaches the
   `LlmClient` runs through `GovernanceKernel.evaluate`, and the kernel is
   pure (no side effects, no I/O, no logging) so its decision is reproducible
   from input alone.
2. **No path skips receipt generation.** Both ALLOW and DENY branches write
   a receipt before returning. The HTTP 400 path explicitly does not, and
   that absence is itself part of the contract: requests that fail schema
   validation never entered the governed flow.
3. **Manifest tampering is detected.** The registry captures a SHA-256 hash
   of each manifest's canonical form at load time. The kernel recomputes the
   hash on every call and fails closed with `MANIFEST_INTEGRITY_FAILURE` on
   any mismatch.
4. **Effect classes the endpoint forbids cannot execute.** v1 forbids
   `WRITE_PRIVILEGED` via configuration; manifests declaring it can be
   loaded for inspection but never run.
5. **The manifest is the authority on which models may be invoked.** The
   kernel — not the controller — resolves the model. A caller-supplied
   `model` outside the manifest's `allowed_models` is denied with
   `MODEL_NOT_ALLOWED`; an absent `model` resolves to `allowed_models[0]`.
   Default resolution is a kernel decision, not a controller fallback:
   calls that omit `model` go through the same code path that enforces
   explicit-model whitelisting, so there is no audit-distinct "default"
   branch the controller could quietly take.
6. **Prompts are never logged unless the manifest opts in.** `log_prompt:
   true` in `redaction_rules` enables a 200-character prefix in the structured
   audit log; raw responses are never logged.
7. **Boot fails closed when configuration is incomplete.** Missing
   `ANTHROPIC_API_KEY`, malformed manifests, missing fields, and duplicate
   `manifest_id` values all abort startup.

## Adversarial test summary

The ten cases from the spec are implemented under
`src/test/java/net/m87/spinelite/adversarial/` and run as part of `mvn
verify`:

| # | Case                                          | Expected                              |
| - | --------------------------------------------- | ------------------------------------- |
| 1 | Tampered manifest at runtime                  | `MANIFEST_INTEGRITY_FAILURE`          |
| 2 | Manifest agent rebinding attack               | `MANIFEST_AGENT_MISMATCH`             |
| 3 | Prompt at `max_prompt_chars + 1`              | `PROMPT_SIZE_VIOLATION`               |
| 4 | `WRITE_PRIVILEGED` manifest in registry       | `EFFECT_CLASS_FORBIDDEN`              |
| 5 | Prompt-injection attempt                      | ALLOW; system prompt is the manifest's, not the user's |
| 6 | Null bytes in the prompt                      | Graceful handling; hash still computed |
| 7 | Unicode normalisation (NFC vs NFD)            | Different hashes by design (raw bytes) |
| 8 | 100 concurrent calls                          | All unique receipts; no deadlock      |
| 9 | Missing `ANTHROPIC_API_KEY` at boot           | Spring context startup fails          |
| 10 | Receipt forgery resistance                   | `verify()` returns `false` on any mutation |

Note: case 1 substitutes the registry's internal map entry rather than
mutating the loaded `ToolManifest` record's fields directly, because JDK 21
disallows reflective writes on record final fields. The substitution is the
realistic threat model and is exactly what the integrity check is designed
to catch.

## What's not in v1

These were intentionally deferred — each gets a v2 ticket, not a hidden
shortcut here.

- **Multi-provider LLM routing.** v1 ships only the Anthropic SDK adapter;
  the `LlmClient` interface is the seam.
- **Database-backed receipt persistence.** v1 stores receipts in an
  in-memory `ConcurrentHashMap` with no eviction. v2 adds a relational store
  and a retention policy.
- **AuthN / AuthZ on the endpoint.** This demo is the gateway pattern, not
  user management. v2 layers a real authenticator in front.
- **GCP / production deployment.** The image runs anywhere; the deployment
  story is proven elsewhere in the M87 portfolio and is out of scope here.
- **Streaming responses.** v1 returns full responses; streaming is a v2
  ergonomic addition that does not change the governance model.

## License

MIT — see [LICENSE](./LICENSE).
