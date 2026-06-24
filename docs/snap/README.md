# SNAP reference index

A **derived, searchable index** of the Indonesian SNAP standard (Standar Nasional Open API
Pembayaran) v1.0.2, used to build and **trace** the gateway's SNAP adapters (e.g. `maybank`).

This is our own technical interpretation — **not** a copy or redistribution of the Bank
Indonesia / ASPI documents. The PDFs are not committed here. Obtain them from ASPI and verify
against the checksums below.

## Provenance

`snap-1.0.2.json` → `meta.sourceDocuments` records, for each authoritative PDF:

- title, version (`1.0.2`), release date (`2024-09`), publisher (BI / ASPI)
- `sizeBytes` and `sha256` so anyone can confirm they hold the same file
- `sourceUrl` (official ASPI download link — fill in)

The PDFs themselves are archived under `docs/snap/sources/` for local reference but are
**gitignored** — never committed or redistributed. Obtain them from the `sourceUrl`s and drop them
there; their checksums must match `meta.sourceDocuments` / `meta.certificationDocuments`.

Verify a downloaded file:

```
shasum -a 256 SNAP_StandarTeknisKeamanan.pdf
# must equal the sha256 in meta.sourceDocuments
```

Official text and per-bank profiles: ASPI developer portal — `apidevportal.aspi-indonesia.or.id`.

## The index

`snap-1.0.2.json` → `entries[]`. Each entry has a **stable `id`** (`snap.<sec|data|meta>.<slug>`),
a one-line `summary` in our words, the `source` section in the official docs, `keywords` for
search, and structured fields where useful (`stringToSign`, `algorithm`, `headers`, `httpMethod`,
`changedIn`). IDs are the contract — don't rename them; add new ones and deprecate old ones.

## Code traceability

Tag the code that implements a spec element with `@SnapSpec`, referencing the index id:

```java
@SnapSpec("snap.sec.transaction.signature.symmetric")
String signTransaction(String method, String path, String token, String body, String timestamp) { ... }
```

`@SnapSpec` (`com.artivisi.paymentgateway.adapter.snap.SnapSpec`) is the single source of the
code↔spec link. `SnapSpecIndexTest` validates the index (unique ids, required fields, checksums
present). A coverage check that every `@SnapSpec` id exists in the index will be wired up with the
first SNAP adapter.

## Maintenance

When ASPI releases a new version: add the new PDF's checksum/version, add a `snap.changelog.<ver>`
entry, update affected entries' `changedIn`, and bump any code/tests the change touches.
