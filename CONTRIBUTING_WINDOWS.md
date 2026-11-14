# Contributing to the SME Ecosystem without Docker (Windows)

This guide explains how a developer on **Windows** can build and contribute to the project even when Docker is not available or desired. The instructions replace the usual `docker compose up -d --build` workflow with native tooling (Gradle, Node.js, Angular, and a local database).

---

## 1. Prerequisites

| Component | Minimum Version | How to install on Windows |
|-----------|----------------|---------------------------|
| **Git** | 2.30+ | <https://git-scm.com/download/win> (use *Git Bash* or *PowerShell*) |
| **Java JDK** | 21 (OpenJDK or Adoptium) | <https://adoptium.net/> or <https://jdk.java.net/21/> |
| **Gradle** | Use the **Gradle Wrapper** (`gradlew`) included in the repo – no separate install needed | The wrapper will download the required Gradle version automatically |
| **Node.js** | 20.x LTS (compatible with Angular CLI 20.3.42) | <https://nodejs.org/download/release/v20.18.0/> (LTS) |
| **npm** | Comes with Node.js | Verify with `npm --version` |
| **PostgreSQL** (optional – required only if you want to run the backend against a real PostgreSQL instance; the backend also works with H2 in‑memory for unit tests) | 15+ | <https://www.postgresql.org/download/windows/> – add `bin` directory to `PATH` |
| **Optional:ngrok / port forwarder** if you need external access | N/A | Not required for local contribution |

> **Important:** All commands in this document assume you are using **Git Bash** for the best compatibility with the shell scripts (`gradlew`, `chmod`), but PowerShell users can adapt the forward‑slash paths.

---

## 2. Get the Code

```bash
# Clone your fork (replace <your‑username> with your GitHub handle)
git clone https://github.com/<your-username>/sme.git
cd sme
```

If you already have a local clone, make sure it is up‑to‑date:

```bash
git pull origin main
```

---

## 3. Environment Configuration

1. **Copy the example env file**

   ```bash
   copy .env.example .env
   ```

2. **Edit `.env`** (using Notepad or any editor) and adjust the following values for a non‑Docker setup:

   ```ini
   # Database connection – used by the Spring profile "postgres"
   POSTGRES_PASSWORD=your_postgres_password   # e.g. "mipay" or any password you set for PostgreSQL
   POSTGRES_USER=mipay                         # optional, defaults to "mipay"
   POSTGRES_URL=jdbc:postgresql://localhost:5432/mipay

   # Application environment
   APP_ENVIRONMENT=sandbox                     # sandbox | uat | production
   APP_JWT_SECRET=change-this-to-a‑strong‑secret  # Must be changed from the sandbox default for any non‑sandbox run
   ```

   *If you prefer to stay with the H2 in‑memory database (default), you can leave `POSTGRES_PASSWORD` unchanged; the backend will use the H2 profile.*

3. **(Optional) Create a local PostgreSQL database**

   ```bash
   # In a PowerShell or Git Bash terminal:
   createdb -U postgres mipay
   ```

   Make sure PostgreSQL is running (`pg_ctlcluster 15 main start` or via the Services MMC).

---

## 4. Building the Backend (Java 21 + Gradle)

The project provides a **Gradle Wrapper** (`gradlew`) so you do not need a separate Gradle installation.

### 4.1 Compile & Create the Executable JAR

```bash
# From the repo root (Git Bash or PowerShell)
./gradlew bootJar
```

*What it does*:  
- Downloads Gradle 8.x if not cached.  
- Compiles the source, runs annotation processing for Lombok.  
- Produces `backend/build/libs/sme-backend.jar`.

### 4.2 Run the Backend

```bash
# Run Spring Boot using the generated JAR (H2 profile by default)
java -jar backend/build/libs/sme-backend.jar
```

- The backend will start on `http://localhost:8080`.
- Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

### 4.3 Running Against a Local PostgreSQL

If you set up PostgreSQL and filled `.env` with `POSTGRES_URL`, start the backend with the postgres profile:

```bash
# Tell Spring to use the "postgres" profile
java -jar -Dspring.profiles.active=postgres backend/build/libs/sme-backend.jar
```

The datasource will then read `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` from the environment (or `.env` if you use `set` / `export`).

### 4.4 Backend Unit Tests (H2 in‑memory)

```bash
./gradlew test
```

- Tests use an in‑memory H2 database, so no external DB is required.
- The test suite pins the JVM to UTC (`-Duser.timezone=UTC`) to avoid timezone‑related flakiness.
- If you have custom mesh certificates for integration tests, place them under `backend/build/test-pki/_vol/` or set the environment variables `MESH_CERT`, `MESH_KEY`, `MESH_CA` (the wrapper will fall back to the default paths).

---

## 5. Building the Frontend (Angular 20)

All frontend work is done with **Node.js** and the Angular CLI that lives in `node_modules`.

### 5.1 Install Node.js Dependencies

```bash
cd frontend
npm ci   # installs exactly the versions locked in package-lock.json
```

### 5.2 Build the Main UI

```bash
npm run build   # equivalent to `ng build`
```

- The production bundle is placed in `frontend/dist/` (and sub‑folders for each portal).

### 5.3 Build Specific Portals

The project has three organisational portals (manufacturer, distributor, supplier) plus an admin portal. Use the following commands from the `frontend` directory:

```bash
# Admin portal
npx ng build admin-portal

# Manufacturer portal
npx ng build manufacturer-portal

# Distributor portal
npx ng build distributor-portal

# Supplier portal
npx ng build supplier-portal
```

> **Tip:** The `npm ci` step must run before any `ng build`; otherwise the Angular build may fail due to missing schematics or incompatible TypeScript versions.

### 5.4 Serve the Frontend Locally

```bash
npm start   # runs `ng serve` → http://localhost:4200
```

- If you built a specific portal, you can serve it by pointing `ng serve` at the appropriate `dist` folder, or simply open the `index.html` from `dist/<portal>/browser/`.

---

## 6. End‑to‑End Local Workflow (no Docker)

1. **Open three terminals** (or use tabs):

   - **Terminal A**: Backend (`java -jar ...` or `./gradlew bootRun`).
   - **Terminal B**: Frontend (`npm start` → serves UI on `:4200`).
   - **Terminal C**: Optional – run tests or lint (`./gradlew test`, `npm run lint` if configured).

2. **Perform your changes**:

   - Edit Java source under `backend/src/main/java/...`.
   - Edit Angular components under `frontend/src/app/...`.
   - Re‑build the affected component (backend JAR or frontend bundle).

3. **Verify**:

   - Backend: browse to `http://localhost:8080/swagger-ui.html` and check that your APIs appear.
   - Frontend: ensure the UI reflects your changes at `http://localhost:4200`.
   - Tests: `./gradlew test` should pass; `npm test` runs Karma/Jasmine unit tests.

4. **Commit & Push**

   ```bash
   git add .
   git commit -m "descriptive commit message"
   git push origin <your-branch>
   ```

5. **Open a Pull Request** on GitHub, referencing any relevant issue. The CI pipeline (GitHub Actions) will typically run the same `./gradlew test` and `npm run build` commands, so your local build should match the CI expectations.

---

## 7. Checklist Before Submitting a PR

- [ ] Code follows the project's coding style (checkstyle/spotless is configured via Gradle).
- [ ] All new/modify unit tests pass (`./gradlew test`).
- [ ] Frontend build succeeds (`npm ci && npm run build`; `npx ng build <portal>` for portals).
- [ ] `.env` does **not** contain sensitive secrets (or they are overridden in the PR).
- [ ] If you changed any database schema, ensure Flyway migrations exist under `backend/src/main/resources/db/fresh` (or disable Flyway for H2 profiles).
- [ ] Documentation (README, comments) is updated if necessary.

---

## 8. Troubleshooting Common Windows Issues

| Problem | Likely Cause | Fix |
|---------|--------------|-----|
| `./gradlew: Permission denied` | Script not marked executable on the filesystem | Open *Git Bash* and run `chmod +x gradlew` (or right‑click → *Properties → Unblock* in Explorer, then retry). |
| `java: command not found` | JDK not in `PATH` | Add the JDK `bin` folder to Windows *Environment Variables* → *Path*, or use the full path: `C:\Program Files\Microsoft\jdk-21\bin\java`. |
| `npm err! missing script: "ng"` | Angular CLI not installed globally | The project uses the local CLI via `npx ng ...`; ensure `npm ci` was run first. |
| `PSQL` errors when starting backend with postgres profile | PostgreSQL not running or port different | Start PostgreSQL service (`services.msc` → *PostgreSQL-xx* → *Start*), verify it listens on `5432`. |
| Test failures related to timezone | Tests expect UTC but host is in another timezone | The wrapper already sets `-Duser.timezone=UTC`; if you run tests manually, add `set JVM_ARGS=-Duser.timezone=UTC` before `gradlew test`. |
| `EACCES` binding port 8080 or 4200 | Another instance still running | Kill previous processes: `taskkill /F /IM java.exe` and `taskkill /F /node.exe` (or use `lsof -i :8080` to find the PID). |

---

## 9. Getting Help

- Open an issue on the repository titled “Windows build help” or ask in the **#development** channel of the project’s Slack/Discord.
- Review the existing docs under `/docs/` for deeper context on UAT test packs, RBAC redesign, etc.
- If you encounter a bug specific to the no‑Docker path, please add a reproduction step to the issue so the maintainers can improve these instructions.

---

*Happy coding! This guide was generated to let Windows developers contribute without requiring Docker. Feel free to suggest improvements via a PR.*