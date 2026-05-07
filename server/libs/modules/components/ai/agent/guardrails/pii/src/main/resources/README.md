# PII

`PII` is a rule-based detector for personally-identifiable information. It runs in the **preflight
stage** of `CheckForViolations` (or `SanitizeText`) and emits matched spans for the parent
advisor's longest-first masking pass.

## What it detects

The built-in entity catalogue covers globally-recognized identifiers and several locale-specific
forms:

- **Global**: `EMAIL`, `PHONE_NUMBER`, `CREDIT_CARD`, `IP_ADDRESS` (v4), `IBAN_CODE`,
  `URL`, `DATE`, `MEDICAL_LICENSE`, `CRYPTO`
- **United States**: `US_SSN`, `US_DRIVER_LICENSE`, `US_PASSPORT`, `US_BANK_NUMBER`, `US_ITIN`
- **United Kingdom**: `UK_NHS`, `UK_NINO`
- **Italy**: `IT_FISCAL_CODE`, `IT_DRIVER_LICENSE`, `IT_VAT_CODE`, `IT_IDENTITY_CARD`,
  `IT_PASSPORT`
- **Spain, Poland, Singapore, Australia, India, Finland**: regional national-ID and passport
  formats

Run `PiiDetector.getPiiDetectionOptions()` in code or open the `Entities` dropdown in the editor
for the live list — new entities are added behind the same property key without a cluster-element
version bump.

## Properties

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `type` | yes | `ALL` | `ALL` scans every built-in entity. `SELECTED` enables only the entities listed in `entities` |
| `entities` | conditional | — | Subset of entity types to scan when `type = SELECTED`. Hidden when `type = ALL` |
| `failMode` | no | `FAIL_CLOSED` | What to do when the check itself cannot run. See the parent guardrails README |

The reviewer-removed `customRegexes` property is intentionally **not** here — for project-specific
patterns, attach the standalone **Custom Regex** action alongside `PII` in the same
`CheckForViolations` parent. Both run in the preflight stage; their masks are merged in the same
longest-first pass so overlap (an internal id contained inside an email, etc.) is handled correctly.

## Two cluster elements

`PII` exposes two cluster elements with the same configuration surface:

- **`piiCheck`** — `CHECK_FOR_VIOLATIONS` type. Emits a `Violation.PatternViolation` so the
  parent advisor blocks the request and lists the entity types in the violation's diagnostic
  info.
- **`piiSanitize`** — `SANITIZE_TEXT` type. Emits the same mask entities but the parent advisor
  rewrites the text instead of blocking.

Both implement `PreflightMasking` so the parent's mask pass merges PII matches with secret-key,
URL, and custom-regex matches.

## Mask placeholders

Each entity type renders as `<TYPE>` — `<EMAIL>`, `<US_SSN>`, `<CREDIT_CARD>`, etc. The placeholder
is stable across runs so downstream tools (a chat-memory store, a logging pipeline) can rely on
matching on the placeholder string.

## Example

Scan everything, block on any hit:

```json
{
  "type": "guardrails/v1/piiCheck",
  "parameters": { "type": "ALL" }
}
```

Scan only EU-relevant identifiers, mask in place:

```json
{
  "type": "guardrails/v1/piiSanitize",
  "parameters": {
    "type": "SELECTED",
    "entities": ["EMAIL", "PHONE_NUMBER", "IBAN_CODE", "IT_FISCAL_CODE", "UK_NINO"]
  }
}
```

## Edge cases

- **Overlap with URL**: an email contains a domain-shaped substring. The advisor's longest-first
  mask pass guarantees the email is masked as a whole `<EMAIL>` rather than splitting into
  `<EMAIL_LOCAL>@<URL>`.
- **Overlap with secret keys**: a JWT looks like base64 with two dots. The PII URL pattern does
  not match JWT shapes, but if you author a custom regex that matches both, the longest match
  wins.
- **Phone numbers**: the detector follows the libphonenumber permissive form, which deliberately
  matches loosely (any 7+ digit run with an optional country code). False positives on order
  numbers and tracking IDs are common; if your domain has structured non-PII numbers, pair PII
  with a Custom Regex allowlist.
