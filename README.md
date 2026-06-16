# ptcgl.dev

Harvests card data and image assets from the Pokémon TCG Live API and stores them in S3.

## Architecture

- **app** — Spring Boot CronJob: authenticates, fetches config docs, upserts card DB, queues asset downloads
- **harvester** — domain logic: plan service, normalizers, DB repositories
- **rainier** — API clients: Rainier config-doc API, CDN, PTCS OAuth2
- **unity** — Unity asset-bundle parser

## Local development

```bash
# Start dependencies
docker compose up -d

# Build
./gradlew build

# Run locally
SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun --args='--plan'
```

## Deployment (Kubernetes + ArgoCD)

### First-time setup

1. Fill in secrets before applying:

   **`infra/postgres/secret.yaml`** — set `password`

   **`infra/app/secret.yaml`** — set `SPRING_DATASOURCE_PASSWORD` (must match postgres secret),
   `MIRROR_S3_ENDPOINT`, `MIRROR_S3_ACCESS_KEY`, `MIRROR_S3_SECRET_KEY`

2. Apply the manifests:

   ```bash
   kubectl apply -k infra/
   ```

3. Seed `auth_state` with a PTCS refresh token (required before the CronJob can run):

   ```bash
   kubectl create job seed-auth \
     --from=cronjob/ptcgl-sync \
     -n ptcgl \
     -- --login --refresh-token=<ory_rt_...token...>
   ```

   The job inserts the refresh token into the DB. On the next scheduled run the CronJob
   exchanges it for an access token automatically (single-use rotation handled by `AuthService`).

   To get a refresh token: authenticate with the Pokémon TCG Live game client; the token
   is a `ory_rt_*` string from the OAuth2 flow with `scope=offline_access`.

### Re-seeding after token expiry

If the refresh token is ever invalidated, run the same job again with a new token:

```bash
kubectl create job reseed-auth-$(date +%s) \
  --from=cronjob/ptcgl-sync \
  -n ptcgl \
  -- --login --refresh-token=<new_ory_rt_token>
```

### Manual plan run

```bash
kubectl create job manual-plan-$(date +%s) \
  --from=cronjob/ptcgl-sync \
  -n ptcgl
```

### ArgoCD (automatic image updates)

Apply the ArgoCD Application (requires [ArgoCD Image Updater](https://argocd-image-updater.readthedocs.io/)):

```bash
kubectl apply -f infra/argocd-app.yaml
```

The Application watches the `infra/` directory in git and re-deploys whenever the
`ghcr.io/zwolsman/ptcgl-sync:latest` digest changes (i.e. on every push to `main`).

## Configuration reference

| Env var | Source | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ConfigMap | Set to `kubernetes` in-cluster |
| `SPRING_DATASOURCE_URL` | ConfigMap | JDBC URL pointing to the in-cluster Postgres |
| `SPRING_DATASOURCE_USERNAME` | Secret | Postgres username |
| `SPRING_DATASOURCE_PASSWORD` | Secret | Postgres password |
| `MIRROR_S3_ENDPOINT` | Secret | S3-compatible endpoint URL |
| `MIRROR_S3_ACCESS_KEY` | Secret | S3 access key |
| `MIRROR_S3_SECRET_KEY` | Secret | S3 secret key |
| `MIRROR_S3_FORCE_PATH_STYLE` | ConfigMap | `true` for Minio/Rustfs, `false` for AWS |
