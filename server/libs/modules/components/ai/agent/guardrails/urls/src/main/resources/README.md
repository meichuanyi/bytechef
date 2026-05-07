# URLs

`URLs` enforces an allowlist on the URLs that may appear in the input. It runs in the
**preflight stage**, recognises four URL shapes (full-scheme URLs, single-colon schemes,
bare IPv4, bare domains), and either flags or masks anything outside the policy.

## Policy

Four properties make up the policy. The defaults are deliberately strict:

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `allowedUrls` | no | empty | Allowlist of URLs, host names, or IP / CIDR ranges. Empty means "everything is blocked" |
| `allowedSchemes` | no | `["https", "http"]` | Schemes that are permitted. Only listed schemes match; everything else (including bare-IP and bare-domain matches, which have no scheme) is rejected |
| `blockUserinfo` | no | `true` | When on, `https://user:pass@host/` is blocked even if the host is allowlisted. Phishing defence |
| `allowSubdomain` | no | `true` | When on, allowing `example.com` also allows `api.example.com`, `staging.api.example.com`, etc. |
| `failMode` | no | `FAIL_CLOSED` | What to do when the check itself cannot run |

### Allowlist entry forms

The `allowedUrls` array accepts three forms; the detector picks the right matcher per entry:

| Form | Example | Matches |
|---|---|---|
| Bare host | `example.com` | Any URL whose host equals `example.com` (plus subdomains if `allowSubdomain` is on). All paths and ports allowed |
| Host with path | `api.example.com/v2/` | Same host match, plus the URL's path must start with the entry's path. Trailing slash matters: `example.com/admin` matches `/admin` and `/admin/users` but not `/administrator` |
| Full URL | `https://localhost:5173` or `https://localhost:5173/admin` | Same host (with optional subdomain), exact port match if the entry specifies a port, and path-prefix match if the entry has a path. If the entry omits the port, any port on the host is allowed |
| CIDR range | `10.0.0.0/24` | Any IPv4 inside the range. `prefix` 0..32 |

The full-URL form was the missing case that caused PR #4915's allow-subdomain bug — entries written
as `http://localhost:5173` are now parsed via `java.net.URI` rather than naive `/`-splitting, so
they match correctly.

## Two cluster elements

- **`urlsCheck`** — flags violations (`HOST_NOT_ALLOWED`, `SCHEME_NOT_ALLOWED`,
  `USERINFO_BLOCKED`, `MALFORMED_URL`). Each blocked URL becomes one
  `Violation.PatternViolation`.
- **`urlsSanitize`** — replaces each blocked URL with `<URL>`. Stable placeholder so any
  downstream regex can post-process the masked text.

The sanitize variant relabels `Block Userinfo` → `Sanitize Userinfo` because masking and blocking
are different operator-facing actions, but the underlying property key
(`blockUserinfo`) is shared.

## Examples

Block everything except your own dev server (the PR #4915 reviewer's case):

```json
{
  "type": "guardrails/v1/urlsCheck",
  "parameters": {
    "allowedUrls": ["http://localhost:5173"],
    "allowedSchemes": ["http", "https"],
    "allowSubdomain": false,
    "blockUserinfo": true
  }
}
```

Allow your prod API and any subdomain, plus an internal RFC-1918 range:

```json
{
  "type": "guardrails/v1/urlsCheck",
  "parameters": {
    "allowedUrls": [
      "https://api.example.com",
      "10.0.0.0/8"
    ],
    "allowedSchemes": ["https"],
    "allowSubdomain": true
  }
}
```

Mask all unrecognised URLs in the LLM's response so they don't leak to the user:

```json
{
  "type": "guardrails/v1/urlsSanitize",
  "parameters": {
    "allowedUrls": ["https://docs.example.com"],
    "allowedSchemes": ["https"]
  }
}
```

## Edge cases

- **IPv6**: parsed as `HOST_NOT_ALLOWED` since the allowlist machinery only carries IPv4 entries.
  IPv6 URLs are flagged by default — if you need to allow them, post-process or extend the
  detector.
- **Punycode IDN**: `xn--exmple-cua.com` is matched as a literal host. An allowlist entry of
  `example.com` does **not** match the punycode form. Add both spellings if you genuinely need
  to permit IDN traffic.
- **Bare IPs**: matched even without a scheme. Their allowlist comparison ignores port
  (a bare `10.0.0.1` matches the CIDR `10.0.0.0/24` regardless of any port the user wrote
  alongside it).
- **Latin prose**: short forms like `e.g.`, `i.e.`, `a.m.`, `p.m.`, `Ph.D` look like
  bare-domain matches to the regex; the detector skips a small allowlist of common
  abbreviations to avoid spamming `SCHEME_NOT_ALLOWED` violations on every paragraph of prose.
- **`javascript:` / `vbscript:` / `data:` schemes**: matched separately as injection vectors.
  Allowlist them via `allowedSchemes` if you genuinely need to permit them; otherwise they're
  flagged regardless of the `allowedUrls` list.
