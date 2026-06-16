# ptcgl.dev — Design

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
- Serving the data (a separate API project consumes Postgres + S3). That API will browse cards/sets as JSON and resolve asset URLs; the data model is designed so card data + all asset links (including inline material manifest JSON) are available from Postgres alone without S3 fetches.
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
- Base: `https://api.studio-prod.pokemon.com`. Responses are **application/json**.
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
ptcgl.dev/                          (Gradle multi-module; Kotlin + Spring Boot)
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
- Per item, idempotently: `IN_PROGRESS` → download bundle → store **raw** to S3 → decode (`unity/`) → store **decoded** (PNG/JSON) to S3 → upsert `card_asset` / `set_asset` links → `DONE` (persist `crc`, `source_hash`, `s3_key`, `etag`).
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
- **Postgres** (dedicated instance) via **Flyway** (migrations) + Spring Data JDBC (typed, blocking — appropriate for a batch job).
- **AWS SDK v2 S3** against the self-hosted S3-compatible endpoint: `endpointOverride`, `forcePathStyle(true)`, dummy region. (MinIO Java client is a drop-in alternative behind the `S3Store` interface.)
- **WebClient / OkHttp** for HTTP.
- CLI subcommands via Spring Boot `ApplicationRunner` (`plan`, `reconcile`, `run`, `login`).

---

## 6. Authentication & single-use refresh-token rotation

Full chain on startup: **PTCS refresh** → `routing/route` → `token/register` (guest) → `token/auth` (PTOK) → studio Bearer.

Because the PTCS refresh token is **single-use and rotates**, rotation is the highest
operational risk.

### PTCS token refresh (verified from decompile)

- **Endpoint:** `POST https://access.pokemon.com/oauth2/token`
- **Content-Type:** `application/x-www-form-urlencoded`
- **Body:** `client_id=tpci-tcg-app&grant_type=refresh_token&refresh_token={token}`
- **Response:** `{ "access_token": "…", "refresh_token": "…", "expires_in": 3600 }` (both tokens rotate; access token lasts ~1 h)

### Strategy (self-hosted, no cloud secret manager)

`auth_state` stores both the **refresh token** and the cached **access token** (+ its
expiry) so the refresh endpoint is called only when necessary:

1. **Startup — fast path (token valid):** read `auth_state`; if `access_token_expires_at > now + 5 min`, use the cached access token directly. No refresh endpoint call, no lock.
2. **Startup — slow path (token expired/absent):**
   - `SELECT … FOR UPDATE` the `auth_state` row (prevents concurrent CronJob instances from double-spending the refresh token).
   - Re-check expiry after acquiring the lock (another run may have refreshed while waiting).
   - If still expired: call the PTCS refresh endpoint → on HTTP 200, write the new `access_token`, `access_token_expires_at`, `refresh_token`, and `refresh_token_updated_at` → **COMMIT immediately**, before any other work.
3. Proceed with the studio chain: `routing/route` → `token/register` → `token/auth (PTOK)` → studio Bearer JWT (in-memory only, ~1 h TTL, not persisted).
4. If the refresh endpoint returns an error (reuse-detected, account suspended, etc.), the job fails loudly ("needs re-login") and exits non-zero. The operator re-seeds via `--login`.

### Bootstrap & break-glass

The *initial* refresh token is seeded via `harvest --login --refresh-token=<token>` (from
a browser OAuth2/PKCE login flow). This writes `auth_state(id=1)` and leaves `access_token`
null so the very first run triggers the slow path immediately. Re-seeding via the same
command re-seeds the row.

```
auth_state(id=1,
    refresh_token             text NOT NULL,
    refresh_token_updated_at  timestamptz NOT NULL,
    access_token              text,               -- cached; null until first refresh
    access_token_expires_at   timestamptz,        -- null until first refresh
    last_studio_iss           text,
    note                      text)
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

- **Initial run: `en` only.** Multi-locale expansion is deferred to M5 (milestone 5). The schema and pipeline are locale-aware from day one, but only `en` is processed initially.
- Configured target locales (e.g. `en, fr, de, it, es, pt-br`) + a **fallback locale** (`en`).
- Phase A iterates `(locale × bucket)` for manifests. Asset names are locale-tagged; the desired-name derivation substitutes the locale tag per target locale.
- **Fallback:** if an asset is absent for a locale, record a fallback link to the fallback locale's asset rather than duplicating bytes (the client itself falls back).
- Card/set localization stored in dedicated tables (§9) so adding a locale never changes the card schema.

---

## 9. Data model (Postgres)

All domain tables (sets, cards, assets) are the **published surface** — consumed by the
separate API. Operational tables (ledger, auth, runs) are internal to the harvester.

### 9.1 Sets

**`set`** — one row per Pokémon TCG set (locale-invariant facts).

| Column | Type | Nullable | Source | Notes |
|---|---|---|---|---|
| `id` | text PK | no | `set-manifest_0.0` manifest `sets[]` | Lowercase set code, e.g. `sv1` |
| `code` | text UNIQUE | no | same | Same as `id`; kept for readability |
| `name` | text | no | _(placeholder = code)_ | No authoritative name source in `set-manifest` yet; API may override |
| `series` | text | yes | `setDetails[code].SeriesId` | e.g. `"Scarlet & Violet"` |
| `release_date` | date | yes | `setDetails[code].OAReleaseDate` | OLE Automation serial (days since 1899-12-30) converted to calendar date |
| `revision` | text | yes | `config_revision.revision` for `set-manifest_0.0` | Used to detect content changes on re-fetch |

Additional fields present in `setDetails` that are **not yet stored** (available for future columns):

| Raw field | Description |
|---|---|
| `MainSetCount` | Number of cards in the main set |
| `MasterSetCount` | Number of cards in the master/full set |
| `SortOrder` | Global display sort order |
| `SeriesSortOrder` | Sort order within the series |
| `SetSortOrder` | Sort order within the set group |
| `SetCategory` | Category enum (e.g. main expansion vs. promo) |

---

**`set_localization`** — locale-specific set names.

| Column | Type | Nullable | Source | Notes |
|---|---|---|---|---|
| `set_id` | text FK → `set.id` | no | | |
| `locale` | text | no | | e.g. `en`, `fr`, `de` |
| `name` | text | no | _(same placeholder as `set.name` for now)_ | Will be populated from per-locale sources when discovered |

Primary key: `(set_id, locale)`.

---

**`set_asset`** — S3 pointers for set imagery.

| Column | Type | Nullable | Source | Notes |
|---|---|---|---|---|
| `set_id` | text FK → `set.id` | no | | |
| `locale` | text | no | | |
| `kind` | `set_asset_kind` | no | | `SYMBOL` or `LOGO` |
| `s3_key` | text | no | Unity bundle internal path | Full `decoded/…` key; verbatim path from the bundle |
| `source_hash` | text | yes | Asset manifest `hash` field | Full content hash; used for change detection |
| `crc` | bigint | yes | Asset manifest `crc` field | Cheap first-pass change probe |

Primary key: `(set_id, locale, kind)`.

---

### 9.2 Cards

**`card`** — one row per card number within a set (locale-invariant facts). The live
`card-database-{set}_{locale}_0.0` DataTable has **63 columns** (verified against the sv1/EN
fixture; column names are stable across sets). The fields below are the subset currently
mapped by `CardDbNormalizer`. Source column names are the raw DataTable column names.

| Column | Type | Nullable | Source column (DataTable) | Notes |
|---|---|---|---|---|
| `id` | text PK | no | `cardID` | e.g. `sv1_1`; unique across all sets |
| `set_id` | text FK → `set.id` | no | `setCode` (lowercased) | Derived from `cardID` prefix if `setCode` absent |
| `number` | text | no | `CompSea Card Number` (fallback: `EN Card #`) | Formatted collector number, e.g. `001` |
| `rarity` | text | yes | `Loc Rarity Code` | Rarity string code, e.g. `C`, `U`, `R`, `RR`, `SAR` |
| `regulation_mark` | text | yes | `Regulations symbol` | Single letter, e.g. `G`, `H` |
| `archetype` | text | yes | `archetypeID` (`System.Int32`) | Numeric gameplay archetype ID |
| `hp` | int | yes | `HP` (`System.Int32`) | Null for Trainer / Energy cards |
| `types` | text[] | no | `EN Type` | Single-letter type codes split on `/`, e.g. `["G"]`, `["R","W"]`; empty array for colorless/typeless |
| `evolves_from` | text | yes | `EN Evolves From` | Pokémon name this card evolves from; null for Basic/non-Pokémon |
| `group_id` | text | yes | `Group ID` | Groups alternate-art variants of the same card |

#### Complete DataTable column reference (all 63 columns, sv1/EN fixture)

All column names and .NET types as they appear in the raw DataTable. Columns currently
mapped by `CardDbNormalizer` are marked **captured**; the rest are available for future
schema expansion.

| DataTable column | .NET type | Status | Notes |
|---|---|---|---|
| `cardID` | String | **captured** → `card.id` | Primary identifier, e.g. `sv1_1` |
| `setCode` | String | **captured** → `card.set_id` | Uppercased in source; lowercased on ingest |
| `seriesCode` | String | not stored | e.g. `SV` |
| `CompSea Card Number` | String | **captured** → `card.number` | Formatted collector number, e.g. `001` |
| `EN Card #` | String | **captured** (fallback) → `card.number` | Legacy format; used when `CompSea Card Number` absent |
| `EN Expansion Denominator` | String | not stored | Total count in set, e.g. `198` |
| `longFormID` | String | not stored | Full identifier, e.g. `Pineco_sv1_1_std_Common_NonFoil_None` |
| `EN Card Name` | String | **captured** → `card_localization.name` | English display name |
| `LocalizedCardName` | String | **captured** (fallback) → `card_localization.name` | Locale-specific name; used when `EN Card Name` absent |
| `EN Format` | String | not stored | Format legality code, e.g. `0` |
| `Regulations symbol` | String | **captured** → `card.regulation_mark` | Single letter, e.g. `G`, `H` |
| `Release Date` | String | not stored | ISO-8601 string, e.g. `2023-03-30T17:00:00.0000000Z` |
| `OA Release Date` | Double | not stored | OLE Automation serial date (same conversion as `set.release_date`) |
| `category` | Byte | not stored | Card category: `1`=Pokémon, `2`=Trainer, `3`=Energy |
| `archetypeID` | Int32 | **captured** → `card.archetype` | Numeric archetype ID |
| `HP` | Int32 | **captured** → `card.hp` | Null for Trainer/Energy |
| `EN Type` | String | **captured** → `card.types` | Single-letter codes, `/`-separated, e.g. `G`, `R/W` |
| `EN Attack Name` | String | **captured** → `card_attack_localization.name` (EN fallback) | First attack name (English; also the locale col for EN) |
| `EN Attack Name 2` | String | **captured** → `card_attack_localization.name` (EN fallback) | Second attack name |
| `EN Attack Name 3` | String | **captured** → `card_attack_localization.name` (EN fallback) | Third attack name |
| `EN Attack Name 4` | String | **captured** → `card_attack_localization.name` (EN fallback) | Fourth attack name |
| `EN Attack Text` | String | **captured** → `card_attack_localization.text` (EN fallback) | First attack rules text (English; also the locale col for EN) |
| `EN Attack Text 2` | String | **captured** → `card_attack_localization.text` (EN fallback) | Second attack rules text |
| `EN Attack Text 3` | String | **captured** → `card_attack_localization.text` (EN fallback) | Third attack rules text |
| `EN Attack Text 4` | String | **captured** → `card_attack_localization.text` (EN fallback) | Fourth attack rules text |
| `{LOCALE} Attack Name` | String | **captured** (non-EN only) → `card_attack_localization.name` | Locale-prefixed column added for non-EN DBs (e.g. `FR Attack Name`); preferred over EN fallback |
| `{LOCALE} Attack Name 2-4` | String | **captured** (non-EN only) → `card_attack_localization.name` | Slots 2-4 locale attack names |
| `{LOCALE} Attack Text` | String | **captured** (non-EN only) → `card_attack_localization.text` | Locale-prefixed attack rules text |
| `{LOCALE} Attack Text 2-4` | String | **captured** (non-EN only) → `card_attack_localization.text` | Slots 2-4 locale attack texts |
| `EN Cost` | String | **not captured** | First attack energy cost, e.g. `CC`, `GGG` |
| `EN Cost 2` | String | **not captured** | Second attack energy cost |
| `EN Cost 3` | String | **not captured** | Third attack energy cost |
| `EN Cost 4` | String | **not captured** | Fourth attack energy cost |
| `Damage` | String | **not captured** | First attack damage value, e.g. `10`, `120+` |
| `Damage 2` | String | **not captured** | Second attack damage |
| `Damage 3` | String | **not captured** | Third attack damage |
| `Damage 4` | String | **not captured** | Fourth attack damage |
| `attackType1` | Int32 | **not captured** | First attack type enum (nullable) |
| `attackType2` | Int32 | **not captured** | Second attack type enum (nullable) |
| `attackType3` | Int32 | **not captured** | Third attack type enum (nullable) |
| `attackType4` | Int32 | **not captured** | Fourth attack type enum (nullable) |
| `attackID0` | Int32 | **not captured** | First attack identifier hash |
| `attackID1` | Int32 | **not captured** | Second attack identifier hash |
| `attackID2` | Int32 | **not captured** | Third attack identifier hash |
| `attackID3` | Int32 | **not captured** | Fourth attack identifier hash |
| `Retreat` | Int32 | **not captured** | Retreat energy cost (number of energies) |
| `EN Weakness Type` | String | **not captured** | Weakness type code, e.g. `R` (Fire) |
| `Weakness Amount` | String | **not captured** | Weakness multiplier, e.g. `2` (for ×2) |
| `EN Resistance Type` | String | **not captured** | Resistance type code |
| `Resistance Amount` | String | **not captured** | Resistance modifier value |
| `EN Evolves From` | String | **captured** → `card.evolves_from` | Pokémon name this evolves from |
| `baseEvolution` | String | not stored | Base evolution asset key, e.g. `Pineco_0007` |
| `evolvesInto` | String | not stored | Comma-separated evolution targets, e.g. `Forretress,Forretress ex` |
| `Ultra Beast?` | String | not stored | Flag; `""` or `"true"` |
| `Group ID` | String | **captured** → `card.group_id` | Groups alternate-art variants |
| `variant` | UInt32 | not stored | Numeric variant identifier |
| `Variant Suffix` | String | not stored | String suffix for variant, e.g. `mph`, `sph` |
| `Set Suffix` | String | not stored | Set number within series, e.g. `1` in `SV1` |
| `CompSea Rarity Code` | String | not stored | Alternate rarity code system |
| `Dust Rarity Code` | String | not stored | Crafting-dust rarity code |
| `Loc Rarity Code` | String | **captured** → `card.rarity` | Display rarity code, e.g. `C`, `U`, `R`, `RR`, `SAR` |
| `Foil Effect` | String | not stored | Foil finish name, e.g. `NonFoil`, `Holo` |
| `Foil Mask` | String | not stored | Foil mask asset name; links to material manifest |
| `Craftable` | Boolean | not stored | Whether the card can be crafted |
| `CraftableDateUnixTime` | Int64 | not stored | Unix timestamp (seconds) when the card became craftable |
| `quantity` | Int32 | not stored | Unknown — always `0` in observed data |
| `extraSearchText` | String | not stored | Pre-built search blob (card name + attack text lowercased) |

> **CardDbNormalizer is incomplete.** Attacks (`EN Attack Name/Text/Cost`, `Damage`), combat
> stats (`Retreat`, `EN Weakness Type`, `EN Resistance Type`), and several other gameplay
> fields are present in the DataTable but not yet stored in Postgres or exposed by the
> domain records. The schema will need new columns (or a `card_attack` child table) before
> the API can serve full card data.

---

**`card_localization`** — locale-specific card names.

| Column | Type | Nullable | Source column | Notes |
|---|---|---|---|---|
| `card_id` | text FK → `card.id` | no | | |
| `locale` | text | no | | e.g. `en` |
| `name` | text | no | `EN Card Name` (fallback: `LocalizedCardName`) | The card's display name in this locale |

Primary key: `(card_id, locale)`.

---

**`card_attack`** — locale-invariant per-attack data (cost and damage are energy codes /
numbers, identical across all locales).

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `card_id` | text FK → `card.id` | no | |
| `slot` | int | no | 1-based position on the card (1..4) |
| `cost` | text | yes | Energy cost string, e.g. `CC`, `GGG`; null when slot unused |
| `damage` | text | yes | Damage value, e.g. `10`, `120+`; null for effect-only attacks |
| `attack_type` | int | yes | `attackType` enum value from DataTable |
| `attack_id` | int | yes | `attackID` hash (signed Int32) from DataTable |

Primary key: `(card_id, slot)`.

---

**`card_attack_localization`** — locale-specific attack name and rules text. Must be upserted after `card_attack` (FK constraint).

| Column | Type | Nullable | Source column | Notes |
|---|---|---|---|---|
| `card_id` | text | no | | |
| `slot` | int | no | | 1-based, matches `card_attack.slot` |
| `locale` | text | no | | e.g. `en`, `fr` |
| `name` | text | no | `{LOCALE} Attack Name[/ 2/3/4]` → fallback `EN Attack Name[/ 2/3/4]` | Locale-specific attack name |
| `text` | text | yes | `{LOCALE} Attack Text[/ 2/3/4]` → fallback `EN Attack Text[/ 2/3/4]` | Rules text; null for attacks with no effect description |

Primary key: `(card_id, slot, locale)`.
Foreign key: `(card_id, slot)` → `card_attack`.

**Locale column naming — verified live against EN and FR fixtures:**

- Non-EN DataTables have more columns than EN (FR: 72 vs EN: 63). Each locale adds its own
  attack name/text columns.
- Attack columns are prefixed with the uppercase locale code: `FR Attack Name`, `FR Attack Name 2`,
  `FR Attack Text`, etc. There is **no** generic `LocalizedAttackName` column — each locale
  adds its own prefixed columns.
- `LocalizedCardName` is the **only** generic localized column; it works across all locales
  (e.g., `LocalizedCardName = Pomdepik` in the FR DB).
- The `EN Attack Name` / `EN Attack Text` columns exist in every locale's DataTable and always
  carry the English value — they serve as the fallback when the locale column is absent.
- `{LOCALE}` is the locale code in uppercase, matching the `cardsLangTag` used to build the
  doc ID (e.g., `fr` → `FR`, `de` → `DE`).
- All locale-invariant fields (HP, Retreat, Damage, EN Cost, weakness/resistance type codes,
  Rarity, category, archetypeID, etc.) are confirmed identical across EN and FR.

---

**`card_variant`** — rarity/finish variants that share the same `card_id`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | bigserial PK | no | Surrogate |
| `card_id` | text FK → `card.id` | no | |
| `variant` | text | no | Rarity suffix, e.g. `mph` (master promo holo), `sph` (special holo) |

Unique constraint: `(card_id, variant)`.

---

**`card_asset`** — S3 pointers and metadata for card imagery.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `card_id` | text FK → `card.id` | no | |
| `locale` | text | no | |
| `kind` | `card_asset_kind` | no | One of: `HIRES`, `THUMB`, `WHITEPLATE`, `ETCH`, `MATERIAL_MANIFEST`, `RAW` |
| `variant` | text | no (default `''`) | Rarity suffix (`mph`, `sph`, …) for `MATERIAL_MANIFEST`; empty string for all other kinds |
| `s3_key` | text | yes | Full `decoded/…` key; verbatim Unity bundle internal path; material manifests reference other assets by these same paths |
| `manifest_json` | jsonb | yes | Populated only for `kind = MATERIAL_MANIFEST`; stored inline so the API resolves image URLs without S3 round-trips |
| `source_hash` | text | yes | Full content hash from asset manifest |
| `crc` | bigint | yes | CRC from manifest entry; cheap change-detection probe |
| `width` | int | yes | Decoded image width in pixels |
| `height` | int | yes | Decoded image height in pixels |

Primary key: `(card_id, locale, kind, variant)`.

`card_asset_kind` meanings:

| Kind | Description |
|---|---|
| `HIRES` | Full-resolution card image (PNG) |
| `THUMB` | Thumbnail card image (`_t` suffix in asset name) |
| `WHITEPLATE` | White-plate overlay image |
| `ETCH` | Etched/texture overlay |
| `MATERIAL_MANIFEST` | Unity material manifest JSON; `manifest_json` populated inline |
| `RAW` | Raw Unity bundle bytes retained for reproducibility |

---

### 9.3 Operational tables

```sql
-- Ledger: asset_object drives resumability (see §4 pipeline)
asset_object(asset_name, locale, bucket, crc, source_hash, s3_key_raw, s3_key_decoded,
             status ENUM(PENDING,IN_PROGRESS,DONE,FAILED_RETRYABLE,SKIPPED,STALE),
             attempts, last_error, lease_until, updated_at,
             primary key(asset_name, locale))
             -- crc: cheap first-pass change probe; source_hash: full content hash

config_revision(doc_id pk, revision, fetched_at)
auth_state(id pk=1, refresh_token, refresh_token_updated_at, last_studio_iss, note)
ingest_run(id pk, started_at, finished_at, planned, done, skipped, rate_limited, summary)
```

`IN_PROGRESS` rows older than `lease_until` are reclaimed to `PENDING` at run start (crash safety).

---

## 9a. Card & set database codec (verified against decompile + live API)

The card/set data is **not** Unity bundles — it comes from the authenticated config
service as base64 payloads that decode to QuickLZ-compressed, little-endian .NET
`DataTable` binaries. Fully reverse-engineered; needs a dedicated Kotlin codec.

### Discovery
- `set-manifest_0.0`:
  - key `manifest` → JSON array of set codes (`["ec","sv1","swsh12", ...]`).
  - key `setDetails` → per-set object `{MainSetCount, MasterSetCount, SeriesId, SortOrder, SeriesSortOrder, SetSortOrder, SetCategory, OAReleaseDate}`. `OAReleaseDate` is an **OLE Automation / Excel serial date** (days since 1899-12-30) → convert.
- `card-databases-manifest_0.0`:
  - key `card-databases-manifest` → JSON array of **templates**, one per set: `["sv1_1_{0}", "bw1_1_{0}", ...]`. `{0}` is the cards language tag; the middle number is a per-set DB version.

### Per-set card DB document
- Doc id = `String.format(template, cardsLangTag.lowercase()) + "_0.0"` → e.g. `sv1_1_en_0.0`. The document has a prefix "card-database-" and suffix "_0.0" need to be added. Example: "card-database-sv1_1_en_0.0"
  - `cardsLangTag = localizationSettings.GetSubstitutionLanguageTag("cards").lowercase()`.
- Fetched via the same `getMultiple` (JSON) path; payload under key **`table`**, `contentType: json`, `contentString` = base64.

### Payload codec (`table` contentString)
1. **base64**-decode.
2. **QuickLZ.decompress** — QuickLZ level-1 stream (`CardDatabase.DataAccess/QuickLZ.cs`; port to Kotlin, existing Java ports exist). `BufferedRealtimeCompressionEngine` = thin wrapper over QuickLZ.
3. Parse as a custom **`System.Data.DataTable`** via **little-endian .NET `BinaryReader`** semantics:
   - `.NET ReadString` = **7-bit-encoded-int (LEB128)** byte-length prefix + UTF-8 bytes.
   - Header: `TableName` (ReadString), `colCount` (Int32 LE), then per column `name` (ReadString) + `typeName` (ReadString); `rowCount` (Int32 LE).
   - Each cell starts with a **marker byte**: `0` = value follows, `1` = `DBNull`, `2` = `null`.
   - Typed values: Boolean(1), Byte/`CardCategory`(1), SByte(1), Int16/UInt16(2 LE), Int32/UInt32(4 LE), Int64/UInt64(8 LE), Single(4 IEEE), Double(8 IEEE), Char(.NET ReadChar), String(7-bit-len+UTF-8), `Byte[]`(Int32 len + bytes), DateTime(Int64 **ticks** → 100ns since 0001-01-01), Decimal(.NET 16-byte: lo/mid/hi/flags Int32s), Guid(16-byte .NET layout), TimeSpan(Int64 ticks).
4. Map columns to domain (same column set as the prior exploration's `Row.Columns`: `cardID`, `EN Card Name`, `EN Card #`, rarity codes, `HP`, evolves, `archetypeID`, `Group ID`, `category`, `variant`, …) → `card` / `card_localization`.

### Implementation tasks
- `QuickLz.decompress(ByteArray): ByteArray` (level-1).
- `DotNetBinaryReader` (little-endian; 7-bit-int strings, decimal, guid, datetime-ticks). **Distinct from the big-endian `UnityFS` reader** — do not share endianness.
- `DataTableCodec.decode(base64): Table` composing the above; `CardDbCodec` / `SetCodec` mapping to domain rows.

---

## 10. Storage layout (S3-compatible, raw + decoded)

```
raw/{locale}/{bucket}/{assetName}     # exact bytes as fetched (UnityFS)
decoded/{asset-bundle-internal-path}  # decoded output; path taken verbatim from the Unity bundle
```

The `decoded/` prefix is the only path we construct. The remainder is the asset's internal path as embedded in the Unity asset bundle — no reconstruction or guessing. Material manifest JSON files reference other assets by these same internal paths, so the references resolve correctly without any translation layer.

The `s3_key` stored in `card_asset` and `set_asset` is the full `decoded/…` key. Material manifest JSON is additionally stored inline in `card_asset.manifest_json` so the API never needs an S3 round-trip to resolve image URLs.

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
