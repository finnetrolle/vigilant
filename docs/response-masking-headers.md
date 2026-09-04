# Response masking and headers

Guardrail-enabled Chat Completions responses are disclosed only after protocol
validation and the final response-policy outcome. The same header contract
applies to ordinary JSON and Server-Sent Events (SSE).

| Response property | `ALLOW` | `MASK` | `BLOCK` | Invalid upstream `502` | Inspection unavailable `503` |
|---|---|---|---|---|---|
| HTTP status | Preserve upstream status | Preserve upstream status | Local `403` | Local `502` | Local `503` |
| Body | Original bytes | Exact source-patched bytes | VIG-29 JSON error | VIG-29 JSON error | VIG-29 JSON error |
| `Content-Type` | Preserve upstream value | Preserve upstream value, including SSE charset parameters | Local JSON | Local JSON | Local JSON |
| `Content-Length` | Preserve the filtered upstream representation | Recalculate from rewritten bytes | Recalculate for local error | Recalculate for local error | Recalculate for local error |
| `Content-Encoding` | Preserve absent or exact `identity` | Preserve absent or exact `identity` | Do not copy upstream | Do not copy upstream | Do not copy upstream |
| Hop-by-hop fields, including `Connection`-nominated fields | Remove with the canonical proxy filter | Remove with the canonical proxy filter | Do not copy upstream | Do not copy upstream | Do not copy upstream |
| Representation validators: `ETag`, `Content-MD5`, `Digest` | Preserve | Remove | Do not copy upstream | Do not copy upstream | Do not copy upstream |
| Request IDs, rate-limit fields, cache directives and other end-to-end metadata | Preserve | Preserve | Do not copy upstream | Do not copy upstream | Do not copy upstream |
| `Retry-After` | Preserve upstream value | Preserve upstream value | Absent | Absent | Local `1` |

`MASK` removes representation validators because their upstream values describe
the original body, not the rewritten representation. Hop-by-hop fields are
connection-scoped and therefore never cross the proxy boundary. `BLOCK`, `502`
and `503` are gateway-owned responses and contain only their VIG-29 response
metadata, never upstream headers.

Compressed guardrail-enabled SSE is an explicit non-goal. Only absent
`Content-Encoding` and exact `identity` are accepted. `gzip` and every other
encoding return `502 invalid_upstream_response`; Vigilant does not decode or
re-encode them.
