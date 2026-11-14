# Contributing – Hybrid Windows Workflow (Reuse Remote IDP & DB on mipay.afriqpay.com)

This guide is for developers on Windows who want to contribute code while **reusing the existing Identity Provider (IDP) and database servers** hosted at **`mipay.afriqpay.com`**.  It combines the native build steps from the previous Windows guides with remote service connections, so you do not need to run PostgreSQL or Keycloak locally.

---

## 1. Overview of the Hybrid Model

| Component | Local? | Remote (mipay.afriqpay.com) | How you connect |
|-----------|--------|-----------------------------|-----------------|
| **Identity Provider (IDP)** | No | ✅ Provides OAuth2 / OpenID Connect, mTLS certificates | Your backend will point to the remote IDP endpoint and use the PKI shipped with the repo (or fetched via `socat` forwarder). |
| **Database** | No | ✅ PostgreSQL instance reachable via `POSTGRES_URL` | The backend reads `POSTGRES_URL` from `.env`; no local DB needed. |
| **Backend (Java 21)** | ✅ Compiled & run locally | ❌ Uses remote DB & IDP | `./gradlew bootJar` → `java -jar` with Spring profile `postgres`. |
| **Frontend (Angular)** | ✅ Built & served locally | ❌ Calls backend APIs (which in turn call remote IDP) | `npm start` → `http://localhost:4200`. |
| **Observability (Swagger, logs)** | ✅ Local UI | ✅ Remote services remain on `mipay.afriqpay.com` | No extra setup required. |

> **Why hybrid?**  
> - Keeps your Windows machine lightweight (no Docker, no local DB spin‑up).  
> - Allows you to develop against the same production‑like IDP and DB that the project uses in its sandbox/uat environments.  
> - Fast iteration: compile locally, run against remote services, and push changes via Git.

---

## 2. Prerequisites (Windows)

| Tool | Minimum Version | Install URL / Notes |
|------|----------------|---------------------|
| **Git** | 2.30+ | <https://git-scm.com/download/win> (Git Bash recommended) |
| **Java JDK** | 21 (OpenJDK or Adoptium) | <https://adoptium.net/> or <https://jdk.java.net/21/> |
| **Gradle Wrapper** | Included (`gradlew`) | No separate install needed |
| **Node.js** | 20.x LTS | <https://nodejs.org/download/release/v20.18.0/> (for frontend) |
| **Code editor** | Any (VS Code, IntelliJ) | Recommended for editing source & `.env` |
| **Network access** | Outbound HTTPS (443) to `mipay.afriqpay.com` | Ensure your corporate firewall allows egress to that host |
| **Optional: ngrok / port forwarder** | If you need to expose a local port to the internet for IDP callback testing | Not required for most contributions |

---

## 3. Get the Code

```bash
git clone https://github.com/<your-username>/sme.git
cd sme
# or, if you already have a clone:
git pull origin main
```

---

## 4. Environment Configuration (Hybrid `.env`)

Copy the example and adjust only the **remote** connection values. Everything else can keep its default.

```bash
copy .env.example .env
```

Edit `.env` (Notepad or any editor) – **only** these lines need changing:

```ini
# -----------------------------------------------------------------
# Remote IDP & DB – point to the hosted services on mipay.afriqpay.com
# -----------------------------------------------------------------

# Remote PostgreSQL (reachable via TLS).  The host is the Cloud‑SQL endpoint.
POSTGRES_URL=jdbc:postgresql://mipay.afriqpay.com:5432/mipay
POSTGRES_USER=mipay               # default user for the remote schema
POSTGRES_PASSWORD=remote-db-password   # ask the repo maintainer or use the placeholder from CI secrets

# Remote Identity Provider (Keycloak‑compatible) 
APP_IDP_URL=https://auth.mipay.afriqpay.com/realms/mipay
APP_IDP_CLIENT_ID=sme-frontend      # the client ID your frontend registers with
APP_IDP_CLIENT_SECRET=change-me-ci   # obtain from the CI/CD secrets or the admin console
APP_ENVIRONMENT=sandbox             # sandbox | uat | production – keep sandbox for dev

# -----------------------------------------------------------------
# Local overrides (these are already set in the repo defaults)
# -----------------------------------------------------------------
APP_JWT_SECRET=sandbox-jwt-secret-change-me-in-production-0123456789
```

> **Important:**  
> - The `POSTGRES_PASSWORD` should **not** be committed if it contains real secrets; you can leave it as the placeholder and the CI pipeline will inject the real value.  
> - `APP_IDP_CLIENT_SECRET` similarly is injected by the CI system; you can use the sandbox secret for local runs.

If you are **only** changing frontend code, you may keep the default `APP_JWT_SECRET` and `APP_ENVIRONMENT=sandbox`; the backend will still talk to the remote IDP because the IDP URL is supplied via environment.

---

## 5. Building & Running the Backend (Remote DB & IDP)

### 5.1 Compile the Backend

```bash
./gradlew bootJar
```

- This produces `backend/build/libs/sme-backend.jar` exactly as in the pure‑Windows guide.

### 5.2 Start the Backend with Remote Profiles

```bash
# Tell Spring to use the "postgres" profile, which reads POSTGRES_URL etc. from the env
java -jar -Dspring.profiles.active=postgres backend/build/libs/sme-backend.jar
```

- The backend will attempt to connect to `mipay.afriqpay.com:5432` for its PostgreSQL data source.
- It will also initialise the **IDP client** using `APP_IDP_URL`, `APP_IDP_CLIENT_ID`, and `APP_IDP_CLIENT_SECRET`.  Those values are read at startup from the environment (`.env` or exported vars).

### 5.3 Verifying the Backend is Reaching the Remote Services

- **Database**: Query the remote DB directly (e.g., via `psql` client or your favourite GUI) to see if the `mipay` schema is reachable.
- **IDP**: Open `http://localhost:8080/realms/mipay/protocol/openid-connect/certs` (if the remote Keycloak exposes its JWKS) or simply hit any auth‑protected endpoint; you should receive a `302` redirect to the remote login page (`https://auth.mipay.afriqpay.com/realms/mipay/login`).

If you see connection refused, double‑check:

1. `POSTGRES_URL` format and network connectivity (ping `mipay.afriqpay.com` from Git Bash).
2. That `APP_IDP_URL` uses `https` and the host is reachable.
3. That `APP_IDP_CLIENT_SECRET` matches what the remote IDP expects (often a 12‑char secret generated in the Keycloak admin console).

---

## 6. Building & Running the Frontend (calls remote backend)

The frontend does **not** need a local IDP or DB; it only calls the backend APIs (which forward to the remote services).

### 6.1 Install Dependencies

```bash
cd frontend
npm ci
```

### 6.2 Start the Frontend

```bash
npm start   # runs `ng serve` → http://localhost:4200
```

- The Angular app will hit `http://localhost:8080/api/...` (the base URL is defined in `src/environments/environment.ts`; the default points to `localhost:8080`).
- Because the backend is already running (see 5.2), the UI will be able to authenticate against the remote IDP via the backend’s OAuth2 flow (e.g., `/oauth/authorize`, token exchange).

### 6.3 Build a Production Bundle (optional)

```bash
npm run build   # ng build
# or portal‑specific:
npx ng build manufacturer-portal
```

- The built bundle can be served statically (see the *Frontend* guide) or pushed to your favourite web host.

---

## 7. Full Hybrid Local Development Loop

1. **Open two (or three) Git Bash terminals**:

   - **Terminal A** – Backend: `java -jar -Dspring.profiles.active=postgres backend/build/libs/sme-backend.jar`
   - **Terminal B** – Frontend: `cd frontend && npm start`
   - **Terminal C** (optional) – Run tests or linters:
     - `./gradlew test`
     - `npm test`

2. **Make your changes**:

   - **Backend**: Edit Java source under `backend/src/main/java/...`, adjust `.env` if you need to change remote connection details.
   - **Frontend**: Edit Angular components under `frontend/src/app/...`.

3. **Re‑build only the changed side**:

   - Backend: `./gradlew bootJar` (re‑use the same JAR; no need to restart if you use `bootRun` with `-Dspring.profiles.active=postgres` and it auto‑reloads).
   - Frontend: `npm run build` (or just `npm start` for live reload).

4. **Verify**:

   - Open `http://localhost:4200` – you should see the login screen that redirects to `https://auth.mipay.afriqpay.com/realms/mipay`.
   - After successful login, the UI will call backend APIs; those will hit the remote PostgreSQL and display data.

5. **Commit & Push**:

   ```bash
   git add .
   git commit -m "add feature X – hybrid Windows contribution"
   git push origin <your-branch>
   ```

   The CI pipeline will also run `./gradlew test` and `npm run build` against the same remote IDs, ensuring your changes don’t break the shared services.

---

## 8. Checklist Before Submitting a Hybrid PR

- [ ] Backend compiles: `./gradlew bootJar` succeeds.
- [ ] Backend tests pass: `./gradlew test` (H2 unit tests still pass; integration tests will attempt the remote DB/IDP – ensure they are not failing due to network).
- [ ] Frontend builds: `npm ci && npm run build` (or portal‑specific build) with no errors.
- [ ] `.env` does **not** contain real secrets; only placeholders or CI‑injected values.
- [ ] If you added new API endpoints, the Swagger UI at `http://localhost:8080/swagger-ui.html` reflects them.
- [ ] You have verified that the front‑end can authenticate via the remote IDP (login flow works).
- [ ] Any environment‑specific configuration is documented in the PR description (e.g., “uses remote DB on mipay.afriqpay.com, IDP realm = mipay”).

---

## 9. Troubleshooting Hybrid Specific Issues

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| `java.net.ConnectException: Connection refused` (backend) | Network block or wrong `POSTGRES_URL` | Ping `mipay.afriqpay.com` from Git Bash; ensure outbound 5432 is allowed. Use the full URL: `jdbc:postgresql://mipay.afriqpay.com:5432/mipay`. |
| `401 Unauthorized` / endless redirect to IDP | Mis‑matched `APP_IDP_CLIENT_ID/SECRET` or `APP_IDP_URL` | Verify the values against the Keycloak admin console for the `mipay` realm. Ask a maintainer for the current secrets if you are new. |
| Frontend shows “Network error” on API calls | Backend not running or listening on a different port | Confirm backend terminal shows “Started … on port 8080”. If you used a custom port, update `environment.ts` `apiUrl`. |
| `psql` command not found (if you try to manually query remote DB) | Local PostgreSQL client not installed | Use a GUI (DBeaver, DataGrip) or the `psql` from a Docker container if you have Docker available; otherwise, rely on the backend’s built‑in connection pool. |
| Test failures related to timezone | Tests assume UTC but your system is in another TZ | The wrapper already adds `-Duser.timezone=UTC`. If you run tests manually, add `set JVM_ARGS=-Duser.timezone=UTC` before `gradlew test`. |
| `EACCES` on port 8080/4200 | Another instance still running | `taskkill /F /IM java.exe` and `taskkill /F /node.exe`. |

---

## 10. Getting Help

- Open an issue labelled “hybrid windows help”.
- Ask in the **#hybrid‑windows** channel (if exists) or the general **#development** channel.
- The maintainers can provide the current `APP_IDP_CLIENT_SECRET` and `POSTGRES_PASSWORD` for the sandbox environment.
- Reference the existing docs under `/docs/` for deeper context on UAT test packs, RBAC redesign, etc.

*Happy contributing! This hybrid workflow lets Windows developers stay productive without Docker while still exercising the real remote IDP and database that the project uses in its sandbox/uat pipelines.*