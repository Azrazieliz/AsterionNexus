# Nexus contract schemas

All `*.v1.schema.json` files describe **target interfaces**, not already running Android services. They are JSON Schema Draft 2020-12, maintained in this Nexus repository.

| Schema | Purpose |
|---|---|
| `registry-item.v1` | Canonical file, locations, optional relations and aliases |
| `transfer-receipt.v1` | Exact source transfer, complete byte evidence and postconditions |
| `job.v1` | Persistent independently recoverable operations |
| `asset-action.v1` | Direct permission-scoped signed app request |
| `asset-result.v1` | Target app outcome and actual output reference |
| `episode-assembly.v1` | Ordered sources, evidence-backed KEEP/CUT/REVIEW EDL and independently verified output |
| `migration-receipt.v1` | Non-destructive donor import, count differences and rollback |

**Important:** Validation of schema syntax or synthetic fixtures does not establish phone/media/provider functionality. Partner contracts require negotiation against actual available signed provider endpoints and explicit owner approval. Unknown versions must be rejected, not interpreted as v1.

Source code for external apps stays in its donor repository until an audited integration. Large file data is handed off via protected content providers/SAF/verified streams rather than embedding private bytes into IPC, Git or JSON evidence. Evidence records should redact private access paths and never contain account credentials. See `../docs/08-ECOSYSTEM-INTEGRATION.md`.
