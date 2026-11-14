# Contributing to the Backend (Java 21 + Spring Boot) on Windows – No Docker

This guide is tailored for **backend developers** who want to contribute to the SME service without using Docker. It focuses on the Gradle‑based Java backend, JDK 21, and optional local PostgreSQL.

---

## 1. Prerequisites (Windows)

| Tool | Minimum Version | Install URL / Notes |
|------|----------------|---------------------|
| **Git** | 2.30+ | <https://git-scm.com/download/win> (use *Git Bash* for best compatibility) |
| **Java JDK** | 21 (OpenJDK or Adoptium) | <https://adoptium.net/> or <https://jdk.java.net/21/> |
| **Gradle** | Use the **Gradle Wrapper** (`gradlew`) shipped with the repo – no separate install needed | The wrapper will download the required Gradle version automatically the first time you run it |
| **PostgreSQL** (optional – required only if you want to run the backend against a real PostgreSQL instance; the backend also works with H2 in‑memory for unit tests) | 15+ | <https://www.postgresql.org/download/windows/> – add the `bin` directory to your system `PATH` |
| **Node.js** (optional – only if you need to run the frontend alongside) | 20.x LTS | Same as frontend guide |
| **Text Editor / IDE** | VS Code, IntelliJ IDEA, or any editor | Recommended: IntelliJ with Spring Boot plugin or VS Code + extensions |

> **Important:** All commands assume you are using **Git Bash** for the `gradlew` permissions, but PowerShell users can adapt the forward‑slash paths.

---

## 2. Get the Code

```bash
# Clone your fork (replace <your‑username> with your GitHub handle)
git clone https://github.com/<your-username>/sme.git
cd sme
```

If you already have a local clone:

```bash
git pull origin main
```

---

## 3. Environment Configuration

1. **Copy the example env file** (from the repo root):

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
   APP_JWT_SECRET=change-this-to-a-strong-secret  # Must be changed from the sandbox default for any non‑sandbox run
   ```

   *If you prefer to stay with the default H2 in‑memory profile, you can leave `POSTGRES_PASSWORD` unchanged; the backend will use the H2 profile automatically.*

3. **(Optional) Create a local PostgreSQL database**

   ```bash
   # In Git Bash or PowerShell:
   createdb -U postgres mipay
   ```

   Make sure PostgreSQL is running (`services.msc` → *PostgreSQL‑15* → *Start*) and listening on the default port `5432`.

---

## 4. Building the Backend (Gradle Wrapper)

The project includes a **Gradle Wrapper** (`gradlew`) so you do not need a separate Gradle installation.

### 4.1 Compile & Create the Executable JAR

From the repo root (Git Bash or PowerShell):

```bash
./gradlew bootJar
```

*What it does*:
- Downloads Gradle 8.x if not cached.
- Compiles the source, runs annotation processing for Lombok.
- Produces `backend/build/libs/sme-backend.jar`.

### 4.2 Run the Backend

#### 4.2.1 Default (H2 in‑memory profile)

```bash
java -jar backend/build/libs/sme-backend.jar
```

- The backend starts on `http://localhost:8080`.
- Swagger UI is available at `http://localhost:8080/swagger-ui.html`.
- No external database is required; Hibernate ddl-auto=update will create the H2 schema on first start.

#### 4.2.2 Running Against a Local PostgreSQL

If you set up PostgreSQL and filled `.env` with `POSTGRES_URL`, start the backend with the `postgres` Spring profile:

```bash
java -jar -Dspring.profiles.active=postgres backend/build/libs/sme-backend.jar
```

- The datasource will read `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` from the environment (or from `.env` if you use `set` / `export` in the shell).

### 4.3 Running Backend Unit Tests (H2 in‑memory)

```bash
./gradlew test
```

- Tests use an in‑memory H2 database, so no external DB is required.
- The test suite pins the JVM to UTC (`-Duser.timezone=UTC`) to avoid timezone‑related flakiness.
- If you have custom mesh certificates for integration tests, place them under `backend/build/test-pki/_vol/` or set the environment variables `MESH_CERT`, `MESH_KEY`, `MESH_CA` (the wrapper will fall back to the default paths).

### 4.4 Running a Specific Spring Profile via Command Line

You can also use `gradlew` tasks:

```bash
./gradlew bootRun -Dspring.profiles.active=postgres
```

This starts the application in development mode, reloading on source changes, using the postgres profile.

---

## 5. IDE Integration (Optional)

### 5.1 IntelliJ IDEA

1. Open the `backend` directory as a project.
2. When prompted, select **Open as Project** → *Gradle*.
3. IDE will import the Gradle wrapper and allow you to run `bootRun`, `test`, etc., from the toolbar.
4. Ensure **JDK 21** is configured in *File → Project Structure → SDK*.

### 5.2 VS Code

1. Install extensions: **Extension Pack for Java**, **Gradle for Java**, **Spring Boot Extension Pack**.
2. Open the `backend` folder.
3. VS Code will detect the Gradle wrapper and provide tasks (`gradlew bootJar`, `gradlew test`) in the *Run and Debug* sidebar.
4. Set the `java.configuration.runtimeVersion` to `21` in `settings.json` if auto‑detection fails.

---

## 6. Full Local Stack (Frontend + Backend)

1. **Start the backend** (as in section 4.2):

   ```bash
   # H2 default
   java -jar backend/build/libs/sme-backend.jar

   # or with postgres profile
   java -jar -Dspring.profiles.active=postgres backend/build/libs/sme-backend.jar
   ```

2. **Start the frontend** (separate Git Bash window) – see the *Frontend* guide:

   ```bash
   cd frontend
   npm start
   ```

3. **Verify**:
   - Backend: `http://localhost:8080/swagger-ui.html`.
   - Frontend: `http://localhost:4200` – UI will call the backend at `http://localhost:8080`.

---

## 7. Testing the Backend

| Command | Description |
|---------|-------------|
| `./gradlew test` | Runs all unit/integration tests using H2. |
| `./gradlew bootRun` | Starts the app in development mode with automatic reload. |
| `./gradlew tasks --group=verification` | Lists available verification tasks (checkstyle, spotless, etc.). |

- **Code style**: The project uses **Spotless** (google-java-format) enforced via Gradle. Run `./gradlew spotlessApply` to auto‑format your code before committing.
- **Checkstyle**: Configured in `build.gradle.kts`; violations will cause the build to fail.

---

## 8. Checklist Before Submitting a Backend PR

- [ ] `./gradlew bootJar` (or `bootRun`) completes without errors.
- [ ] `./gradlew test` passes (all unit and integration tests).
- [ ] `./gradlew spotlessApply` has been run and the changes are committed.
- [ ] No `secret` values (e.g., JWT secret) are hard‑coded in source; they should be read from `.env` or system properties.
- [ ] If you added new DB entities or changed schemas, ensure a Flyway migration exists under `backend/src/main/resources/db/fresh` **or** keep Flyway disabled for the H2 profile (as is default).
- [ ] Updated any relevant documentation (README, comments) if API contracts changed.
- [ ] Verified that Swagger UI (`/swagger-ui.html`) reflects the new/changed endpoints.

---

## 9. Troubleshooting Common Windows Issues

| Problem | Likely Cause | Fix |
|---------|--------------|-----|
| `./gradlew: Permission denied` | Script not marked executable on the filesystem | Open **Git Bash** and run `chmod +x gradlew` (or right‑click the file → *Properties → Unblock* in Explorer, then retry). |
| `java: command not found` | JDK not in `PATH` | Add the JDK `bin` folder to Windows *Environment Variables* → *Path*, or use the full path: `C:\Program Files\Microsoft\jdk-21\bin\java`. |
| `Error: Could not find or load main class` | Wrong JAR built or wrong Java version | Verify you ran `./gradlew bootJar` with JDK 21; check `java -version` outputs `21.x`. |
| `PSQL` errors when starting backend with postgres profile | PostgreSQL not running or port mismatch | Start PostgreSQL service (`services.msc` → *PostgreSQL‑15* → *Start*), verify it listens on `5432`. |
| Test failures related to timezone | Tests expect UTC but host is in another timezone | The wrapper already sets `-Duser.timezone=UTC`; if you run tests manually, add `set JVM_ARGS=-Duser.timezone=UTC` before `gradlew test`. |
| `EACCES` binding port 8080 or 4200 | Another instance still running | Kill previous processes: `taskkill /F /IM java.exe` and `taskkill /F /node.exe` (or use `lsof -i :8080` to find the PID). |
| `Unable to access jarfile` | JAR not yet built | Run `./gradlew bootJar` first. |

---

## 10. Getting Help

- Open an issue labelled “backend windows help”.
- Ask in the project’s **#backend** channel (Slack/Discord).
- Reference the existing docs under `/docs/` for deeper context on UAT test packs, RBAC redesign, etc.

*Happy coding! This guide lets Windows backend developers contribute without Docker.*