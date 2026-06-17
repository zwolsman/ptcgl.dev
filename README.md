# ptcgl.dev

Mirrors Pokémon TCG Live card data and image assets into self-hosted storage and exposes them through a REST API and web frontend.

## Architecture

Four deployables, all built from `docker/`:

| Module | Role | Image |
|---|---|---|
| `sync` | Spring Boot CronJob — authenticates, fetches config docs, upserts card DB, queues asset downloads | `ghcr.io/zwolsman/ptcgl-sync` |
| `api` | Spring Boot REST API — serves card/set data and asset URLs | `ghcr.io/zwolsman/ptcgl-api` |
| `web` | React Router v7 frontend (SSR) | `ghcr.io/zwolsman/ptcgl-web` |
| `assets` | Nginx proxy — forwards `assets.ptcgl.dev/{key}` to the S3 bucket | `ghcr.io/zwolsman/ptcgl-assets` |

Gradle modules: `unity`, `rainier`, `harvester`, `sync`, `api`

- **harvester** — domain logic: sync service, normalizers, DB repositories
- **rainier** — API clients: Rainier config-doc API, CDN, PTCS OAuth2
- **unity** — pure-Kotlin Unity asset-bundle parser

## Local development

```bash
# Start Postgres + RustFS (S3-compatible) + assets proxy
docker compose up -d

# Build all JVM modules
./gradlew build

# Run the sync job locally
SPRING_PROFILES_ACTIVE=local ./gradlew :sync:bootRun --args='--sync --latest'

# Run the API locally
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# Run the frontend dev server (talks to the API on :8080)
cd web && npm run dev
```

When adding npm dependencies, regenerate the lock file inside the Docker environment to avoid macOS/Linux platform-dependency mismatches:

```bash
docker run --rm -v $(pwd)/web:/app -w /app node:lts-alpine npm install
```

## Deployment (Kubernetes + ArgoCD)

### First-time setup

1. Fill in secrets before applying:

   **`infra/postgres/secret.yaml`** — set `password`

   **`infra/sync/secret.yaml`** — set `SPRING_DATASOURCE_PASSWORD`, `MIRROR_S3_ENDPOINT`, `MIRROR_S3_ACCESS_KEY`, `MIRROR_S3_SECRET_KEY`

   **`infra/api/secret.yaml`** — set `SPRING_DATASOURCE_PASSWORD`

2. Update `infra/api/configmap.yaml` — set `MIRROR_API_ASSET_BASE_URL` to the public assets hostname.

3. Apply the manifests:

   ```bash
   kubectl apply -k infra/
   ```

4. Seed `auth_state` with a PTCS refresh token (required before the CronJob can run):

   Edit `infra/sync/seed-auth-job.yaml` and replace `REPLACE_ME` with the token, then apply:

   ```bash
   kubectl apply -f infra/sync/seed-auth-job.yaml
   ```

   The job inserts the refresh token into the DB. On the next scheduled run the CronJob
   exchanges it for an access token automatically (single-use rotation handled by `AuthService`).

   To get a refresh token: authenticate with the Pokémon TCG Live game client; the token
   is a `ory_rt_*` string from the OAuth2 flow with `scope=offline_access`.

### Re-seeding after token expiry

Edit the token in `infra/sync/seed-auth-job.yaml` and re-apply. The job has `ttlSecondsAfterFinished: 300`
so Kubernetes cleans it up automatically; if the name conflicts, delete it first:

```bash
kubectl delete job ptcgl-seed-auth -n ptcgl --ignore-not-found
kubectl apply -f infra/sync/seed-auth-job.yaml
```

### Manual sync run

```bash
kubectl create job manual-sync-$(date +%s) \
  --from=cronjob/ptcgl-sync \
  -n ptcgl
```

### ArgoCD (automatic image updates)

Apply the ArgoCD Application (requires [ArgoCD Image Updater](https://argocd-image-updater.readthedocs.io/)):

```bash
kubectl apply -f infra/argocd-app.yaml
```

The Application watches `infra/` in git and re-deploys all four images automatically on every push to `main`.

## Configuration reference

### sync

| Env var | Source | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ConfigMap | `kubernetes` in-cluster, `local` for local dev |
| `SPRING_DATASOURCE_URL` | ConfigMap | JDBC URL — `jdbc:postgresql://postgres:5432/ptcgl` |
| `SPRING_DATASOURCE_USERNAME` | Secret | Postgres username |
| `SPRING_DATASOURCE_PASSWORD` | Secret | Postgres password |
| `MIRROR_S3_ENDPOINT` | Secret | S3-compatible endpoint URL |
| `MIRROR_S3_ACCESS_KEY` | Secret | S3 access key |
| `MIRROR_S3_SECRET_KEY` | Secret | S3 secret key |
| `MIRROR_S3_BUCKET` | ConfigMap | S3 bucket name |
| `MIRROR_S3_REGION` | ConfigMap | S3 region |
| `MIRROR_S3_FORCE_PATH_STYLE` | ConfigMap | `true` for RustFS/MinIO, `false` for AWS |

### api

| Env var | Source | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | ConfigMap | JDBC URL — `jdbc:postgresql://postgres:5432/ptcgl` |
| `SPRING_DATASOURCE_USERNAME` | Secret | Postgres username |
| `SPRING_DATASOURCE_PASSWORD` | Secret | Postgres password |
| `MIRROR_API_ASSET_BASE_URL` | ConfigMap | Public base URL for asset images |

### assets proxy

| Env var | Source | Description |
|---|---|---|
| `ASSET_S3_UPSTREAM` | ConfigMap | S3 bucket base URL — requests are forwarded here |
