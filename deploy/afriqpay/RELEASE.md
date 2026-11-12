# Digest-pinned release candidate

Run the manual **Build release images** workflow from the commit to release. It publishes nine GHCR images and writes their immutable `ghcr.io/...@sha256:...` references to the job summaries. Keep the workflow run URL, commit SHA and all nine references in the release record.

On the host, copy those references into a private `release.env` alongside `.env`:

```dotenv
BACKEND_IMAGE=ghcr.io/owner/repo/backend@sha256:...
ADMIN_PORTAL_IMAGE=ghcr.io/owner/repo/admin-portal@sha256:...
MANUFACTURER_PORTAL_IMAGE=ghcr.io/owner/repo/manufacturer-portal@sha256:...
DISTRIBUTOR_PORTAL_IMAGE=ghcr.io/owner/repo/distributor-portal@sha256:...
SUPPLIER_PORTAL_IMAGE=ghcr.io/owner/repo/supplier-portal@sha256:...
GATEWAY_IMAGE=ghcr.io/owner/repo/gateway@sha256:...
IDP_IMAGE=ghcr.io/owner/repo/idp@sha256:...
RBAC_IMAGE=ghcr.io/owner/repo/rbac@sha256:...
COMMS_IMAGE=ghcr.io/owner/repo/comms@sha256:...
```

Use the same checked-out commit as the workflow run for Compose files, config and database migrations. From `deploy/afriqpay`, validate the two rendered configurations before deploying:

```sh
docker compose --env-file .env --env-file release.env -f docker-compose.yml -f release.override.yml config --quiet
docker compose --env-file .env --env-file release.env -f ../../trustplane/docker-compose.yml -f trustplane.override.yml -f ../../trustplane/release.override.yml config --quiet
```

**Order matters.** Deploy the trustplane stack first: its `pki-init` exports the backend's restricted trust material to the external volume `trustplane_backend-pki`, which the mipay backend mounts. Then run `./preflight.sh` and only deploy the mipay stack when it passes; otherwise the backend fails to start on the missing volume.

The overrides remove app image builds and require all nine image variables. Check that every value contains `@sha256:` before deployment. For an approved deployment, pull and start each stack with these same `-f` options, using `docker compose pull` followed by `docker compose up -d --no-build`. Save `release.env` with the release record; restoring the prior file and running the same commands provides an image rollback. Database changes may require a separate rollback plan.

The Postgres, Alpine and other base images still use version tags. Pinning those digests, promoting candidates between environments, and an automated post-deploy image inventory remain to be done.
