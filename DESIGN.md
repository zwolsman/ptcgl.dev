# ptcgl-mirror — Design

A standalone, resumable service that mirrors the Pokémon TCG Live ("Rainier") content
delivery network into self-hosted storage: it authenticates, discovers the authoritative
asset manifests, downloads the relevant (card/set) bundles for every supported locale,
decodes them in pure Kotlin, stores both **raw** and **decoded** artifacts in an
S3-compatible bucket, and normalizes the card/set databases into Postgres so a separate
API can serve them.

This project is **fully isolated** from `ptcgl.online`. It shares no code or database with
it; the proven Unity decoder and the API/auth learnings from that exploration are *ported*
in cleanly.

---

## 1. Goals & non-goals

### Goals
- Mirror the **current** PTCGL content tree (raw + decoded) into self-hosted S3-compatible storage.
- Normalize the **card** and **set** databases into Postgres, linked to their assets.
- Support **multiple locales** (not just `en`), with documented fallback behaviour.
- Be **resumable**: a run interrupted by rate limiting or failure resumes on the next run with no lost or duplicated work. Critical for the multi-run initial bulk load.
- Be **incremental** in steady state: only changed config docs / buckets / assets are reprocessed.
- Run unattended as a Kubernetes `CronJob`.

### Non-goals
- Serving the data (a separate API project consumes Postgres + S3).
- Mirroring irrelevant assets (gameplay VFX, shaders, board art, fonts, audio, etc.).
- Real-time updates. Content drops are ~weekly; a scheduled mirror is sufficient.
- Re-implementing the game's websocket/FlatBuffers realtime protocol beyond what is needed to read config documents.

---

## 2. Context — how the CDN & API actually work (verified live)

These facts were established empirically against production and from two decompiles. They
are the foundation of the design; if they drift, the affected module is the blast radius.

**Content (CDN) — public, unauthenticated.**
- `GameSettings.json` is fetched from `https://cdn.studio-prod.pokemon.com/rainier/GameSettings/{appVersion}/GameSettings.json` and is publicly readable.
- It contains per-platform `{platform}_contentpath`, the authoritative asset base. **The host is not stable** — it migrated `cdn.studio-prod.pokemon.com` → `cdn.studio-preprod.pokemon.biz` between client 1.38 and 1.39. **Always read `contentpath` at runtime; never hardcode it.**
- Asset bundles, the per-bucket manifests, legal text, and localization gzips are all public `GET`s (empty auth headers in the client). The S3 origin returns **403 `AccessDenied` for missing keys** (no anonymous `ListBucket`), which is indistinguishable from "pruned" — callers must tolerate 403.

**Manifests.**
- The bucket list ("directories") is the config document `asset-bundle-manifest_0.0`, key `manifest` → `{"directories":[...]}`.
- Buckets are `10101_0000` (the full base catalogue, ~40k entries) plus dated incremental drops `YYYYMMDD_1700`. Later buckets **override** earlier entries by hash.
- Per bucket, per locale: `{contentpath}{bucket}/manifest_{locale}_{bucket}` is a `UnityFS` bundle wrapping a single `AssetManifest` MonoBehaviour whose `assetList` entries are `{assetName, crc, hash, dependencies, s3Folder}`.
- Engine: Unity `6000.3.5f2`. Manifest type: `TPCI.AssetBundleSystem.AssetManifest/ManifestEntry`.

**Control plane (REST) — requires a logged-in account.**
- Base: `https://api.studio-prod.pokemon.com`. Responses are **FlatBuffers** binary.
- Bootstrap chain (all verified returning 200):
  1. `POST /user/v1/external/routing/route` `{clientTypeAccessKey}` → regional `apiEndpoint` (e.g. `https://api.us-east-1.studio-prod.pokemon.com`).
  2. `POST {api}/account/v1/external/token/register` `{clientTypeAccessKey, clientId}` → **guest** JWT (`iss:guest`).
  3. `POST {api}/account/v1/external/token/auth` `{authToken:<PTCS access token>, authType:"PTOK"}` (Bearer guest) → **studio** JWT (`iss:ptcs3`).
  4. `POST {api}/config/v1/external/configdocument/getMultiple` `{"requests":[{"id":...}]}` (Bearer studio) → config docs (+ `revision`).
- Auth header is `Authorization: Bearer <jwt>` (confirmed in `TokenHolder`/`StudioJwt`).
- **The guest token alone is NOT sufficient**: every config document returns `10102 User access denied`. A real PTCS access token (browser OAuth2 + PKCE login) is required to upgrade the guest token. The PTCS **refresh token is single-use** and rotates on every refresh.

**Locale model.**
- Manifests and asset names are locale-tagged: `manifest_{locale}_{bucket}`, asset names like `{set}_{locale}_{number}` (+ `_t` thumbnail). The client swaps locale tags (`LocaleTag.ReplaceLocaleTagInString`) and falls back to a configured fallback locale when an asset is absent for the requested locale.
- The card/set databases carry localized fields (`EN Card Name`, `LocalizedCardName`, …) and may be published per-locale via `card-databases-manifest_0.0` / `set-manifest_0.0` (to be confirmed in M3).

---

## 3. Principles

1. **Isolated.** Own repo, own Postgres, own S3 prefix, own lifecycle. No coupling to `ptcgl.online`.
2. **Desired-state reconciliation.** Compute what *should* exist, persist it as a ledger, then reconcile reality toward it. The ledger row status **is** the checkpoint — this is what makes the job resumable and incremental without bespoke checkpoint files.
3. **Manifest-driven.** Every asset originates from a parsed manifest entry with a content hash. No filename guessing.
4. **Derive-then-intersect.** The *wanted* assets are derived from the normalized card/set DB (× locales) and intersected with the merged manifest. This yields "relevant only" and "cards/sets ↔ assets linked" in one operation.
5. **Locale as a first-class dimension** across manifests, the ledger, storage keys, and the schema.
6. **Idempotent & tolerant.** Re-processing a completed item is a no-op; 403/pruned and transient failures are first-class outcomes, not crashes.

---

## 4. Architecture

```
ptcgl-mirror/                       (Gradle multi-module; Kotlin + Spring Boot)
  unity/          Ported pure-Kotlin Unity decoder (UnityBundle, SerializedFile,
                  TypeTreeReader, Texture2DDecoder, Lz4/Bc, EndianBinaryReader).
                  Framework-free library module.
  rainier/        Pokémon API surface: AuthClient (+ rotation), ConfigDocClient
                  (+ FlatBuffers/JSON codec), CdnClient, GameSettingsClient,
                  per-host RateLimiter.
  harvester/      Reconciliation pipeline, persistence (jOOQ/JDBC), S3 store,
                  card/set normalization, locale handling.
  app/            Spring Boot entrypoint, wiring, configuration, CLI subcommands.
```

### Pipeline (two phases per run)

**Phase A — Plan (cheap, metadata only, always completes).**
1. Authenticate (§6).
2. `getMultiple` the control docs: `asset-bundle-manifest_0.0`, `card-databases-manifest_0.0`, `set-manifest_0.0`. Compare each `revision` against `config_revision`; skip unchanged.
3. Parse + upsert the **card** and **set** domain tables, including per-locale localization rows.
4. Read `GameSettings.json` → resolve `contentpath` per platform (dynamic host).
5. For each `(locale, bucket)`: fetch + parse `manifest_{locale}_{bucket}`, merge across buckets by hash → current asset map per locale.
6. **Derive desired assets** (card hires/thumb names per locale, set symbols/logos) ∩ manifest → upsert into the `asset_object` ledger with `source_hash`, `bucket`, `locale`, target `s3_key`, status `PENDING` (new/changed) / leave `DONE` (unchanged) / `STALE` (no longer desired).

**Phase B — Reconcile (throttled, resumable, may span runs).**
- Select `status IN (PENDING, FAILED_RETRYABLE)` ordered by priority (newest sets first), under the rate limiter + a per-run budget.
- Per item, idempotently: `IN_PROGRESS` → download bundle → store **raw** to S3 → decode (`unity/`) → store **decoded** (PNG/JSON) to S3 → upsert `card_asset` links → `DONE` (persist `source_hash`, `s3_key`, `etag`).
- Transient error → `FAILED_RETRYABLE` (attempt count + backoff). 403/pruned → `SKIPPED`.
- **On 429 or budget exhaustion: stop cleanly, exit 0**, leaving remaining work `PENDING`. Next CronJob tick resumes from the ledger.

Status machine (the resume log):
```
PENDING ─▶ IN_PROGRESS ─▶ DONE
   ▲             ├─▶ FAILED_RETRYABLE ─(backoff)─▶ PENDING
   │             └─▶ SKIPPED (403 / pruned)
   └─(hash changed on next Plan)
STALE (no longer desired)
```

---

## 5. Tech stack

- **Kotlin + Spring Boot** (DI, configuration, actuator/health, Micrometer).
- **Coroutines** for bounded-concurrency download/decode.
- **Postgres** (dedicated instance) via **Flyway** (migrations) + **jOOQ** or Spring Data JDBC (typed, blocking — appropriate for a batch job).
- **AWS SDK v2 S3** against the self-hosted S3-compatible endpoint: `endpointOverride`, `forcePathStyle(true)`, dummy region. (MinIO Java client is a drop-in alternative behind the `S3Store` interface.)
- **WebClient / OkHttp** for HTTP.
- CLI subcommands via Spring Boot `ApplicationRunner` (`plan`, `reconcile`, `run`, `login`).

---

## 6. Authentication & single-use refresh-token rotation

Chain: `routing/route` → `token/register` (guest) → **refresh PTCS token** → `token/auth` (PTOK) → studio Bearer.

Because the PTCS refresh token is **single-use and rotates**, rotation is the highest
operational risk. Strategy (self-hosted, no cloud secret manager):

- The live refresh token lives in Postgres `auth_state` (single row), co-located with the ledger so updates are **transactional**.
- Rotation: `SELECT ... FOR UPDATE` the row → call the refresh endpoint → on HTTP 200, **write the new refresh token and COMMIT immediately**, before any other work.
- Access/studio JWTs are in-memory only (~1 h TTL), minted fresh per run.
- **Bootstrap & break-glass:** the *initial* refresh token is seeded from a Kubernetes `Secret` (or `harvest login --refresh-token=…`). If the chain ever breaks (reuse-detected), the job fails loudly ("needs re-login") and the operator re-seeds via the same path.

```
auth_state(id=1, refresh_token text, refresh_token_updated_at, last_studio_iss, note)
```

---

## 7. Config documents & FlatBuffers

`ConfigDocClient.getMultiple(ids)` posts to `/config/v1/external/configdocument/getMultiple`.
Responses are FlatBuffers. Plan:
1. First test whether `Accept: application/json` is honoured (the client negotiates a codec). If so → Jackson, done.
2. Otherwise generate a **minimal Kotlin FlatBuffers reader** for `GetConfigDocumentsResponse` (mirror the `com.pokemon.studio.contracts.client_config` contracts; `flatc` can emit Kotlin). Each document key carries a JSON `contentString`.

Expose `revision` per document to drive incremental Phase A.

---

## 8. Locale handling

- Configured target locales (e.g. `en, fr, de, it, es, pt-br`) + a **fallback locale** (`en`).
- Phase A iterates `(locale × bucket)` for manifests. Asset names are locale-tagged; the desired-name derivation substitutes the locale tag per target locale.
- **Fallback:** if an asset is absent for a locale, record a fallback link to the fallback locale's asset rather than duplicating bytes (the client itself falls back).
- Card/set localization stored in dedicated tables (§9) so adding a locale never changes the card schema.

---

## 9. Data model (Postgres)

```sql
-- Domain (published; consumed by the separate API)
set(id pk, code, name, series, release_date, revision)
set_localization(set_id fk, locale, name, symbol_s3_key, logo_s3_key, primary key(set_id, locale))
card(id pk, set_id fk, number, rarity, regulation_mark, archetype, hp,
     types, evolves_from, group_id, ...)          -- locale-invariant facts
card_localization(card_id fk, locale, name, ...,  primary key(card_id, locale))
card_variant(id pk, card_id fk, variant, ...)
card_asset(card_id fk, locale, kind ENUM(HIRES,THUMB,MANIFEST,RAW),
           s3_key, source_hash, width, height,    primary key(card_id, locale, kind))

-- State / ledger (drives resumability)
asset_object(asset_name, locale, bucket, source_hash, s3_key_raw, s3_key_decoded,
             status ENUM(PENDING,IN_PROGRESS,DONE,FAILED_RETRYABLE,SKIPPED,STALE),
             attempts, last_error, lease_until, updated_at,
             primary key(asset_name, locale))
config_revision(doc_id pk, revision, fetched_at)
auth_state(id pk=1, refresh_token, refresh_token_updated_at, last_studio_iss, note)
ingest_run(id pk, started_at, finished_at, planned, done, skipped, rate_limited, summary)
```

`IN_PROGRESS` rows older than `lease_until` are reclaimed to `PENDING` at run start (crash safety).

---

## 10. Storage layout (S3-compatible, raw + decoded)

```
raw/{locale}/{bucket}/{assetName}                 # exact bytes as fetched (UnityFS)
cards/{locale}/{setId}/{number}/hires.png
cards/{locale}/{setId}/{number}/thumb.png
cards/{locale}/{setId}/{number}/manifest.json     # decoded MonoBehaviour fields
sets/{locale}/{setId}/symbol.png
sets/{locale}/{setId}/logo.png
```
Dedupe by `source_hash`; objects carry correct content-types. Raw is retained for
reproducibility and to allow re-decoding without re-downloading.

---

## 11. Rate limiting & resumability

- **Per-host token-bucket limiter** (the config/auth API is the constrained surface; the CDN/S3 is permissive). Honour `Retry-After`; exponential backoff + jitter on 429/5xx.
- **Run budget** (max items or wall-clock) → predictable run length; remaining work stays `PENDING`.
- `CronJob` `concurrencyPolicy: Forbid` + a Postgres **advisory lock** prevent overlapping runs.
- Initial load drains over multiple runs; steady state drains the `revision`-diff in one. Same code path.

---

## 12. Packaging & operations

- **Docker:** multi-stage Gradle build → JRE 17 runtime; one image, subcommand selects behaviour.
- **K8s:** `CronJob` (off-peak, non-`:00` minute), `Forbid`, `startingDeadlineSeconds`; modest CPU (manifest parse + texture decode are CPU-bound); dedicated Postgres; S3 + refresh-token secrets via env / mounted `Secret`.
- **Observability:** `ingest_run` summary rows + Micrometer metrics (pending backlog, processed, 429s, auth failures); alert if backlog stops draining or auth fails.

---

## 13. Milestones

1. `unity/` ported + tested against `card.bin`/`thumb.bin` fixtures.
2. `rainier/`: auth + single-use rotation + ConfigDocClient (resolve JSON-vs-FlatBuffers) + rate limiter — tested live.
3. Schema + Flyway; Phase A producing the ledger + normalized cards/sets; **discover set symbol/logo asset naming** from `set-manifest`.
4. Phase B: resumable reconcile, budget, 429 handling, raw+decoded S3 upload, `card_asset` linking.
5. Locale expansion (multi-locale manifests, fallback, localization tables).
6. Dockerfile + CronJob + secrets + metrics.

---

## 14. Conventions

- **Conventional Commits** for every unit of work (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, …), one logical change per commit.
- Modules are independently testable; `unity/` and `rainier/` stay framework-light enough to reuse elsewhere.

---

## 15. Known risks (see review)

Account/ToS dependency, refresh-token fragility vs. resumable multi-run loads, Unity-format
drift, FlatBuffers decode, locale storage multiplication, copyright exposure of served
assets, and CDN/API contract drift. These are tracked and critiqued separately.
