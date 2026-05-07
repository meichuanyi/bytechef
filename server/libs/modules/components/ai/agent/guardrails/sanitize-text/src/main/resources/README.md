# Sanitize Text

`SanitizeText` is the **rewrite-in-place** counterpart of `CheckForViolations`. It runs the
configured child sanitizers over the input (or the LLM's response) and replaces matched spans
with placeholders rather than blocking the request.

The two parents share the same child catalogue and the same advisor architecture; the only
difference is the verdict path: `CheckForViolations` blocks on any violation;
`SanitizeText` rewrites and forwards.

## Where to use it

The most common pattern is wiring `SanitizeText` to the **outbound** side of an agent — run it
on the LLM's response before the user sees it, so anything the model accidentally surfaces (PII
from a tool result, a leaked URL from a documentation passage, a secret embedded in a code
sample) is masked.

For inbound text you usually want `CheckForViolations` instead — blocking is the right default
for adversarial signals like jailbreak attempts, NSFW content, off-topic redirects.

```
                          inbound                outbound
user input  →  CheckForViolations  →  LLM  →  SanitizeText  →  user-visible reply
```

## Compatible children

`SanitizeText` accepts the `Sanitize` variant of every preflight rule-based check:

- `piiSanitize`
- `secretKeysSanitize`
- `urlsSanitize`
- `customRegexSanitize`
- `keywordsSanitize`
- `llmPiiSanitize`

LLM-stage classifiers (Jailbreak, NSFW, Topical Alignment, Custom) do not have sanitize
variants — they emit verdicts on whole inputs, not span-level matches, so masking doesn't apply.
For those checks use `CheckForViolations`.

## Properties

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| (no parent-level properties) | — | — | All configuration lives on the child cluster elements |

`SanitizeText` itself is a thin parent; the per-sanitizer properties (allowlists, thresholds,
fail mode) live on the child sanitizers. A `MODEL` child is required only if you attach
`llmPiiSanitize`; pure rule-based sanitizers work without a model.

## Mask merging

When multiple sanitizers fire on overlapping spans (an email contains a domain that's also
matched by URLs; a JWT contains base64 fragments), the advisor merges every sanitizer's mask
entities and applies them **longest-first**. The longest match wins, so the output is
`<EMAIL>` rather than `<EMAIL_LOCAL>@<URL>`.

This is the same merge logic `CheckForViolations` uses for its preflight stage, just applied to
sanitize-only output.

## Example

Outbound mask: scrub PII, secrets, and unrecognised URLs from the LLM's response:

```json
{
  "type": "guardrails/v1/sanitizeText",
  "extensions": {
    "clusterElements": {
      "sanitizeText": [
        { "type": "guardrails/v1/piiSanitize",        "parameters": { "type": "ALL" } },
        { "type": "guardrails/v1/secretKeysSanitize", "parameters": { "permissiveness": "BALANCED" } },
        {
          "type": "guardrails/v1/urlsSanitize",
          "parameters": {
            "allowedUrls": ["https://docs.example.com"],
            "allowedSchemes": ["https"],
            "allowSubdomain": true
          }
        }
      ]
    }
  }
}
```

Inbound + outbound: block adversarial inputs at the gate, mask anything that sneaks through the
LLM:

```json
[
  { "type": "guardrails/v1/checkForViolations",
    "extensions": { "clusterElements": {
      "model": { "type": "openAi/v1/model", "parameters": { "model": "gpt-4o-mini" } },
      "checkForViolations": [
        { "type": "guardrails/v1/jailbreak", "parameters": {} },
        { "type": "guardrails/v1/nsfw",      "parameters": {} },
        { "type": "guardrails/v1/piiCheck",  "parameters": { "type": "ALL" } }
      ]
    } } },
  { "type": "agenticAi/v1/run", "parameters": { "...": "..." } },
  { "type": "guardrails/v1/sanitizeText",
    "extensions": { "clusterElements": { "sanitizeText": [
      { "type": "guardrails/v1/piiSanitize" },
      { "type": "guardrails/v1/secretKeysSanitize" }
    ] } } }
]
```

## Fail mode

Sanitizers carry the same `Fail Mode` property as check guardrails. When a sanitizer cannot run,
FAIL_CLOSED still **blocks** — even though `SanitizeText` itself is non-blocking, a failed
sanitizer means the masking pass was incomplete and the operator chose security-first. FAIL_OPEN
forwards the unsanitized text and records the failure under `guardrail.skippedFailures`.
