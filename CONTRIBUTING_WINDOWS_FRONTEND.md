# Contributing to the Frontend (Angular) on Windows – No Docker

This guide is tailored for **frontend developers** who want to contribute to the SME UI without using Docker. It assumes a Windows environment and focuses on the Angular part of the monorepo.

---

## 1. Prerequisites (Windows)

| Tool | Minimum Version | Install URL / Notes |
|------|----------------|---------------------|
| **Git** | 2.30+ | <https://git-scm.com/download/win> (use *Git Bash* for best compatibility) |
| **Node.js** | 20.x LTS (compatible with Angular CLI 20.3.42) | <https://nodejs.org/download/release/v20.18.0/> |
| **npm** | Comes with Node.js | Verify with `npm --version` |
| **Angular CLI** | 20.3.42 (included via `package.json` devDependencies) | Installed automatically by `npm ci`; no global install needed |
| **Text Editor** | Any (VS Code, Notepad++, etc.) | Recommended: VS Code with Angular extensions |

> **PowerShell users** can prefix commands with `./` or use `cmd /c`. The guide assumes *Git Bash* for consistency.

---

## 2. Get the Code

```bash
# Clone your fork (replace <your‑username> with your GitHub handle)
git clone https://github.com/<your-username>/sme.git
cd sme
```

If you already have a clone:

```bash
git pull origin main
```

---

## 3. Install Node.js Dependencies

From the **frontend** directory:

```bash
cd frontend
npm ci   # installs exactly the versions locked in package-lock.json
```

- This installs `@angular/cli@^20.3.42` and all other dev dependencies.
- If you get SSL/certificate errors, run `npm config set strict-ssl false` in Git Bash and retry.

---

## 4. Build the Frontend

### 4.1 General Production Build

```bash
npm run build   # equivalent to `ng build`
```

- The output is placed in `frontend/dist/` with separate folders for each portal (`admin-portal`, `manufacturer-portal`, `distributor-portal`, `supplier-portal`).

### 4.2 Build a Specific Portal

```bash
npx ng build admin-portal
npx ng build manufacturer-portal
npx ng build distributor-portal
npx ng build supplier-portal
```

- Each portal’s bundle ends up under `frontend/dist/<portal>/browser/`.

### 4.3 Development Live Reload

```bash
npm start   # runs `ng serve` → http://localhost:4200
```

- Hot module replacement (HMR) is active; changes to `.ts`, `.html`, `.css` are reflected instantly.

---

## 5. Serving the UI Locally (no backend needed for static preview)

If you only need to preview HTML/JS changes:

```bash
# Serve the just‑built portal folder using Python's simple HTTP server (built‑in on Windows via Git Bash)
cd frontend/dist/<portal>/browser
python -m http.server 4200   # then open http://localhost:4200
```

Or use VS Code's **Live Server** extension on the `dist/<portal>/browser` folder.

---

## 6. Running the Full Local Stack (Frontend + Backend)

1. **Start the backend** (see the *Backend* guide or use the pre‑built JAR):
   ```bash
   # From the repo root
   java -jar backend/build/libs/sme-backend.jar
   ```
   - Default runs with H2 in‑memory DB on `http://localhost:8080`.

2. **Start the frontend** (in a separate Git Bash window):
   ```bash
   cd frontend
   npm start
   ```
   - The UI will call the backend at `http://localhost:8080` (configurable via `environment.ts` if needed).

3. **Verify**:
   - Open `http://localhost:4200` – you should see the login/landing page.
   - Swagger UI at `http://localhost:8080/swagger-ui.html` confirms the backend is up.

---

## 7. Testing the Frontend

```bash
npm test   # runs Karma + Jasmine unit tests
```

- Test files live under `frontend/src/app/...-spec.ts`.
- No external services are required; tests mock HTTP calls.

---

## 8. Checklist Before Submitting a Frontend PR

- [ ] `npm ci && npm run build` succeeds with no warnings/errors.
- [ ] Portal‑specific builds (`npx ng build <portal>`) also succeed.
- [ ] `npm test` passes (or any new tests are added with a clear rationale).
- [ ] Code follows the project's Prettier/ESLint config (prettier is configured in `package.json`).
- [ ] UI changes are reflected in the built `dist` folder and committed (`dist/` is git‑ignored, so only source changes are needed).
- [ ] Updated README or comments if new components/paths were added.

---

## 9. Troubleshooting Common Windows Issues

| Problem | Likely Cause | Fix |
|---------|--------------|-----|
| `npm err! missing script: "ng"` | Angular CLI not in PATH | Ensure you ran `npm ci` from the `frontend` directory; the local CLI is invoked via `npx ng`. |
| `Error: EPERM: operation not permitted` on `node_modules` | Antivirus or permission issue | Run Git Bash as *Administrator* temporarily, or exclude `frontend/node_modules` from real‑time scanning. |
| `Cannot find module` after editing `package.json` | Inconsistent lockfile | Delete `package-lock.json` and rerun `npm ci` (or `npm install`).
| Build fails with `Type X has no property Y` | TypeScript version mismatch | Re‑run `npm ci` to lock to the exact TypeScript version defined in `package.json`. |
| `ng serve` port 4200 already in use | Another instance still running | Kill the process: `taskkill /F /IM node.exe` (or use `lsof -i :4200` in PowerShell). |

---

## 10. Getting Help

- Open an issue labelled “frontend windows help”.
- Ask in the project’s **#frontend** channel (Slack/Discord).
- Reference the existing docs under `/docs/` for deeper context on UI patterns, RBAC, etc.

*Happy coding! This guide lets Windows frontend devs contribute without Docker.*