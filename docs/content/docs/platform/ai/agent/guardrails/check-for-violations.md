---
title: Check For Violations
description: The blocking parent action that runs configured guardrails and short-circuits the request when any child flags a violation
---

`Check For Violations` is the **blocking** parent of the guardrails system. It runs every configured child guardrail against the input. If any child returns a violation, the request is short-circuited and a blocked response is emitted carrying the violation details on the response metadata. The LLM is never called.

Use **Check For Violations** for adversarial signals where blocking is the right default — jailbreak attempts, NSFW content, off-topic inputs, secret leaks, PII exposure.

---

## Properties

| Property | Description |
|---|---|
| **Blocked Message** | The text returned to the user when a violation blocks the request. Defaults to "I cannot process this request due to content policy." |
| **Customize System Message** | If on, lets you override the system message used by LLM-stage child guardrails. Most operators don't need this — it's an escape hatch for highly customised setups |
| **System Message** | The custom system message (visible only when **Customize System Message** is on) |

The body of the action runs through the configured cluster element children — that's where the actual checking happens.

---

## Cluster Elements

`Check For Violations` accepts the following children:

- **Model** (required if any LLM-stage child is attached) — a single shared `ChatClient` that every LLM-based child uses for classification. A small fast model (gpt-4o-mini, claude-3-5-haiku, gemini-1.5-flash) is sufficient.
- **Check For Violations children** — one or more guardrail checks. The full catalogue:
  - **Preflight (rule-based)**: PII, Secret Keys, URLs, Custom Regex
  - **LLM stage**: Keywords, Jailbreak, NSFW, Topical Alignment, Custom, LLM PII

If you don't attach a **Model** child, only rule-based checks (PII, Secret Keys, URLs, Custom Regex, Keywords) work. Attaching an LLM-stage child without a Model child is a configuration error and forces fail-closed.

---

## How It Aggregates

Every child runs, even after a sibling flags a violation. The advisor does not short-circuit on the first hit. The returned violations capture matches, classifier scores, and any execution failures side by side, so the operator can see all findings at once.

When a check cannot run (LLM outage, missing Model, bad configuration), an execution-failure violation is emitted alongside the rest — and the advisor's fail-mode determines whether the failure blocks (Fail Closed) or is recorded and forwarded (Fail Open). The operator sees both regardless: under Fail Closed the failure shows up in the blocked-response metadata; under Fail Open it shows up in the `guardrail.skippedFailures` metadata key.

Configuration errors (invalid regex, unparseable threshold, missing Model child) are special-cased to **always** fail closed, regardless of the user's Fail Mode setting. Operator bugs aren't transient outages, and silently allowing requests through a guardrail that's been broken for weeks is worse than blocking until someone fixes it.

---

## Preflight Stage

Masking rule-based guardrails (PII, Secret Keys, URLs, Custom Regex) run in the preflight stage. When they detect a match, two things happen:

1. The violation is recorded.
2. The matched substring is masked with its placeholder (`<EMAIL>`, `<AWS_ACCESS_KEY>`, `<URL>`).

LLM-based guardrails (Jailbreak, NSFW, Topical Alignment, Custom, LLM PII) then run against the **masked** text. This prevents PII from being sent to the classifier model while still giving it enough context to judge prompt-injection, safety, and on-topic-ness signals.

Keyword checks run in the LLM stage, not preflight. They see PII / URL / secret-masked text so keyword lists don't need to anticipate every variant of an email local-part or a URL fragment.

---

## Example

A typical inbound chain — block adversarial inputs, secret leaks, PII:

```json
{
  "type": "guardrails/v1/checkForViolations",
  "parameters": {
    "blockedMessage": "I cannot process this request due to content policy."
  },
  "extensions": {
    "clusterElements": {
      "model": {
        "type": "openAi/v1/model",
        "parameters": { "model": "gpt-4o-mini" }
      },
      "checkForViolations": [
        { "type": "guardrails/v1/piiCheck",        "parameters": { "type": "ALL" } },
        { "type": "guardrails/v1/secretKeysCheck", "parameters": { "permissiveness": "BALANCED" } },
        { "type": "guardrails/v1/jailbreak",       "parameters": { "threshold": 0.7 } }
      ]
    }
  }
}
```
