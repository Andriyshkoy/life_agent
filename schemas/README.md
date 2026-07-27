# Life Agent schema contracts

These Draft 2020-12 schemas deliberately separate current MVP persistence from
future extraction candidates.

| Contract | Role |
|---|---|
| `mvp-event-payloads.schema.json` | Closed payload union for the seven canonical MVP event kinds |
| `life-event.schema.json` | Immutable local-pending or server-committed MVP revision |
| `sync-wire.schema.json` | M2 push, per-operation result, bootstrap and incremental-pull messages |
| `capture-envelope.schema.json` | Capture provenance before canonical materialization |
| `event-payloads.schema.json` | Broader post-MVP extraction candidate catalog |
| `extraction.schema.json` | Post-MVP voice/text extraction result |

`life-event` has no client-controlled owner/person field. Before enrollment it
uses `installation_id` and `local_owner_id`; the server enriches a committed
revision with the authenticated `device_id` and derives its internal
`person_id` outside the document.

## Validation

Run from the repository root:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-ci.txt
python scripts/validate_schemas.py
```

The harness:

- registers every `$id` locally, so no validator performs a network lookup for
  `life-agent.local`;
- checks every schema against the Draft 2020-12 metaschema;
- enables UUID/date/date-time format assertions explicitly;
- validates local, committed, push/ACK/bootstrap and future extraction fixtures;
- checks representative negative schema and semantic cases.

Production Kotlin and backend validators must implement the same registry and
format behavior. Loading `life-event` or `extraction` without their registered
resources is unsupported.

## Wire invariants

For `push_batch_request`:

- HTTP `Idempotency-Key` must equal the body `batch_id`;
- authenticated device identity must equal `device_id`;
- `batch_content_sha256` is the SHA-256 of RFC 8785/JCS request bytes with only
  that digest field omitted;
- each `body_sha256` is the SHA-256 of the corresponding canonical operation
  body;
- byte-identical retry preserves `batch_id`, bytes, digest and membership;
- per-operation replay identity is `operation_id + body_sha256`;
- ACK is returned only after event revision/tombstone, parents, current-pointer
  decision, receipt and `server_sequence` commit atomically.

Schema validation is necessary but does not compare an HTTP header, auth claim
or hash. Those checks happen before materialization.

## Mandatory semantic validation

JSON Schema cannot express every cross-field or database invariant. Android,
API and CI must reject:

- an end instant before its start;
- a fake IANA timezone or disagreement among UTC instant, local datetime,
  timezone, offset and local date;
- evidence JSON Pointers that do not resolve;
- duplicate wellbeing dimensions, meal item IDs, fact IDs or operation IDs;
- self-parent, cross-event ancestry, cycles or invalid branch ordering;
- sleep stages outside the session, overlapping stages or incorrect duration;
- transcript/audio evidence bounds or quotes that do not match the source;
- a source version without its source record and Health Connect identity;
- request/response IDs, ordinals, hashes or device identity that do not match;
- a purge generation or server cursor that moves backward or crosses owners.

Same-event ancestry, ownership, authenticated channel permissions, purge
watermarks and cursor monotonicity remain service/database checks because their
required state is intentionally not embedded in one event document.
