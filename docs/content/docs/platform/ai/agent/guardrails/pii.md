---
title: PII
description: Rule-based detection of personally-identifiable information — emails, phones, SSNs, IBANs, credit cards, IPs, and locale-specific identifiers
---

`PII` is a rule-based detector for personally-identifiable information. It runs in the **preflight stage** of `Check For Violations` (or `Sanitize Text`) and emits matched spans for the parent advisor's longest-first masking pass.

---

## What It Detects

The built-in entity catalogue covers globally-recognized identifiers and several locale-specific forms:

| Locale | Entities |
|---|---|
| **Global** | `EMAIL`, `PHONE_NUMBER`, `CREDIT_CARD`, `IP_ADDRESS` (v4), `IBAN_CODE`, `URL`, `DATE`, `MEDICAL_LICENSE`, `CRYPTO` |
| **United States** | `US_SSN`, `US_DRIVER_LICENSE`, `US_PASSPORT`, `US_BANK_NUMBER`, `US_ITIN` |
| **United Kingdom** | `UK_NHS`, `UK_NINO` |
| **Italy** | `IT_FISCAL_CODE`, `IT_DRIVER_LICENSE`, `IT_VAT_CODE`, `IT_IDENTITY_CARD`, `IT_PASSPORT` |
| **Spain, Poland, Singapore, Australia, India, Finland** | Regional national-ID and passport formats |

Open the **Entities** dropdown in the editor for the live list — new entities are added behind the same property without a version bump.

---

## Properties

| Property | Description |
|---|---|
| **Type** | `All` scans every built-in entity. `Selected` enables only the entities listed in **Entities** |
| **Entities** | Subset of entity types to scan (visible only when **Type** is `Selected`) |
| **Fail Mode** | What to do when the check itself cannot run |

For project-specific patterns (internal ticket IDs, study identifiers, custom account formats), attach the standalone **Custom Regex** action alongside `PII` in the same `Check For Violations` parent. Both run in the preflight stage; their masks merge in the same longest-first pass so overlap (an internal id contained inside an email, etc.) is handled correctly.

---

## Two Variants

`PII` exposes two cluster elements with the same configuration surface:

- **PII (check)** — emits a violation so the parent advisor blocks the request and lists the entity types that fired in the violation diagnostic.
- **PII (sanitize)** — emits the same mask entities but the parent advisor rewrites the text instead of blocking.

Both feed into the parent's mask pass, so PII matches merge with secret-key, URL, and custom-regex matches in a single longest-first redaction.

---

## Mask Placeholders

Each entity type renders as `<TYPE>` — `<EMAIL>`, `<US_SSN>`, `<CREDIT_CARD>`, etc. The placeholder is stable across runs so downstream tools (a chat-memory store, a logging pipeline) can rely on matching on the placeholder string.

---

## Examples

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

---

## Edge Cases

- **Overlap with URL detection**: an email contains a domain-shaped substring. The longest-first mask pass guarantees the email is masked as a whole `<EMAIL>` rather than splitting into `<EMAIL_LOCAL>@<URL>`.
- **Overlap with secret keys**: a JWT looks like base64 with two dots. The PII URL pattern doesn't match JWT shapes, but if you author a custom regex that overlaps, the longest match wins.
- **Phone number false positives**: the detector follows a permissive form, which deliberately matches loosely (any 7+ digit run with an optional country code). Order numbers and tracking IDs may match. If your domain has structured non-PII numbers, pair PII with a Custom Regex allowlist or rely on a more restrictive entity subset via **Type: Selected**.
