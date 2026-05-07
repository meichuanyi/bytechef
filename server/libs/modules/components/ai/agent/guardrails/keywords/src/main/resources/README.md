# Keywords

`Keywords` flags or masks any of a configured list of words when they appear in the input. It
runs in the **LLM stage** of `CheckForViolations` (after preflight masking) so the keyword
matcher sees text where PII / secrets / URLs are already replaced with placeholders.

This is a deliberate design choice: a keyword list authored against raw input would break the
moment the secret-key detector ate the prefix or the PII detector replaced the local-part of an
email. By matching against masked text, the operator doesn't have to anticipate every variant the
preflight stage produces.

## Properties

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `keywords` | yes | — | List of words to detect |
| `caseSensitive` | no | `false` | When off, matching is case-insensitive (default). When on, matches must match casing exactly |
| `failMode` | no | `FAIL_CLOSED` | What to do when the check itself cannot run |

## Word-boundary matching

`KeywordMatcher` uses Unicode-aware word boundaries (`\p{L}|\p{N}|_`) so a keyword `"cat"`:

- **does** match: `"the cat sat"`, `"cat,"`, `"cat."`, `"cat!"` (punctuation boundary)
- **does not** match: `"caterpillar"`, `"category"`, `"cats"` (letter on either side)

This is symmetric — if your keyword itself starts or ends with a non-word character (e.g.,
`":hashtag"`, `"#promo"`), the matcher relaxes the boundary on that side automatically. You
don't need to escape regex metacharacters; the matcher quotes them for you.

## Two cluster elements

- **`keywordsCheck`** — emits a `Violation.PatternViolation` listing the matched keywords.
- **`keywordsSanitize`** — replaces each match with the literal placeholder `[KEYWORD]`. (Unlike
  PII / Secret Keys, every keyword shares one placeholder — there's no per-keyword type.)

## Example

Block any of three competitor names:

```json
{
  "type": "guardrails/v1/keywordsCheck",
  "parameters": {
    "keywords": ["CompetitorA", "CompetitorB", "CompetitorC"],
    "caseSensitive": false
  }
}
```

Mask profanity in the LLM's outbound response:

```json
{
  "type": "guardrails/v1/keywordsSanitize",
  "parameters": {
    "keywords": ["damn", "hell", "..."],
    "caseSensitive": false
  }
}
```

## When NOT to use

- For **structured patterns** (account numbers, ticket IDs, URLs), use Custom Regex instead.
  Keywords are literal strings.
- For **fuzzy matching** ("anything that mentions our brand even with typos"), use `Custom`
  with an LLM classifier prompt. Literal keywords don't catch typos or alternate spellings.
- For **PII** (emails, phone numbers, etc.), use `PII` — the rule-based detector is designed
  for these shapes and word-boundary keyword matching would miss most of them.

## Edge cases

- **Empty keyword list** is treated as a configuration error and forces fail-closed.
- **Whitespace-only entries** are stripped silently. A list with `["", "  ", "real"]`
  effectively becomes `["real"]`.
- **Trailing punctuation in the keyword itself** is stripped before matching, so a list entry
  like `"foo,"` matches `"foo"` rather than requiring the comma. Authoring keywords with
  trailing punctuation is silently forgiving.
