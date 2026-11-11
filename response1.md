# Namibian KYC / Onboarding Compliance — Verified Reference

**Sources:** Financial Intelligence Act 13 of 2012 (as amended by Act 6 of 2023), BIPA Beneficial Ownership page, FIC Guidance Note 01 of 2025 (Identification and Verification of Beneficial Ownership), NamRA Registration & Taxpayer Services FAQ, PwC VAT in Africa — Namibia, Bank of Namibia (banking system), greenAGENT / Namibian Pages bank branch codes (updated 1 June 2016), CDQ business identifier metadata, Altius Group country formats for Namibia.

---

## 1. Registration number & tax ID — format checks

### Company / business registration number (BIPA)
Issued by the Business and Intellectual Property Authority (BIPA).

| Attribute | Detail |
|-----------|--------|
| **Format** | Optional 2-letter prefix + 3–10 digits, slash or hyphen sometimes used |
| **Regex** | `^([A-Z]{2})?\d{3,10}$` |
| **Normalisation** | Strip spaces, dots, hyphens and slashes; uppercase |
| **Check digit** | None |
| **Examples** | `2017/1055` (Gondwana Holdings), `CY/1960/2051` (NamPower), `CC/2020/06396` (JoCham Investments CC), `78/01799` (Standard Bank Namibia), `89/485` (old format) |
| **Entity types** | (Pty) Ltd, Close Corporation (CC), Section 21 non-profit, public company |

### Tax ID — TIN (NamRA / ITAS)
Issued by the Namibia Revenue Agency via the Integrated Tax Administration System.

| Attribute | Detail |
|-----------|--------|
| **Format** | **8 digits** |
| **Regex** | `^\d{8}$` |
| **Example** | `73486341` |
| **Migration note** | Legacy 7-digit taxpayer references were migrated by prefixing `0` to reach 8 digits |
| **VAT compulsory registration** | NAD 1,000,000 taxable supplies in any 12-month period |
| **VAT voluntary registration** | From NAD 200,000 |

### Tax payment reference (TRN) — if you need to store/validate payment references
Structure: `1` (payment type) + `2` (NamRA office code) + `8` (TIN) + `2` (tax type) + `6` (period `ccyymm`) = **19 digits**.  
Example: `1050709649913201805`.

---

## 2. Namibian banks & branch codes

**Authorised banking institutions (Bank of Namibia):** Bank Windhoek, First National Bank Namibia, Standard Bank Namibia, Nedbank Namibia, Bank BIC Namibia, Letshego Bank Namibia, Banco Atlántico (foreign branch).

### Branch codes by bank (5–6 digits)

| Bank | Example branch codes |
|------|---------------------|
| **Bank Windhoek** | Windhoek `481972`, Swakopmund `481772`, Walvis Bay `481872`, Katima Mulilo `482273`, Otjiwarongo `481573` |
| **First National Bank Namibia** | Windhoek `280172`, Swakopmund `280472`, Katima Mulilo `280475`, Otjiwarongo `280673` |
| **Nedbank Namibia** | Windhoek South `461629`, Swakopmund `461052`, Katima Mulilo `461076` |
| **Standard Bank Namibia** | Windhoek `082372`, Swakopmund `082172`, Rundu `085173`, Katima Mulilo `080672` |

**Universal Branch Codes (Netcash / interbank):**
- Bank of Namibia: `980172`
- First National Bank Namibia: `282672`
- Standard Bank Namibia: `087373`

**Format check for branch code field:** `^\d{5,6}$`

> **Source caveat:** Branch-code lists were last updated 1 June 2016 (greenAGENT / Namibian Pages). Confirm the current code with the bank before using in production. Namibia does **not** use IBAN.

---

## 3. Beneficial ownership threshold

**Uniformly 25%** under Schedule 5 of the Financial Intelligence Act 13 of 2012 (as amended by Act 6 of 2023) and confirmed by FIC Guidance Note 01 of 2025 and BIPA.

| Rule | Detail |
|------|--------|
| **Primary test** | Natural person who directly or indirectly holds **25% or more** of shares, voting rights or other ownership interest |
| **Fallback (if no one hits 25%)** | Senior management; right to appoint/remove majority of board; power to materially influence decisions/policy; derives substantive economic benefits (dividends, profit rights, use of assets); influence or ultimate control by any other means |
| **Pyramidal / indirect ownership** | Percentages are multiplied down the chain; if the indirect result is ≥25%, the person is a beneficial owner |
| **Different share classes** | Consider both ownership % and voting rights % separately |
| **Family-owned business** | Family members’ stakes may be combined if they act together as a family-controlled interest |
| **Nominees** | Both nominee and nominator may be beneficial owners |
| **Trusts** | Settlor, trustee(s), protector (if any), beneficiaries/classes, and anyone exercising ultimate control |
| **Partnerships** | 25%+ ownership interest or 25%+ of votes on amending the agreement |

> **Recommendation:** Your placeholders of 25% for PSP and 10% for EMI should both be **25%** — Namibian law does not prescribe a lower threshold for electronic money institutions.

---

## 4. Monthly transaction volume

**Not codified in Namibian AML/KYC law.** The Financial Intelligence Act, BIPA rules, and FIC guidance do **not** prescribe monthly transaction-volume bands for customer onboarding.

- **Internal risk appetite only:** You should define your own tiers (e.g. Low / Medium / High monthly NAD volume) and document them in the institution’s risk assessment required by FIA s.20A.
- **Statutory volume triggers that exist (tax, not KYC):** VAT registration at NAD 1,000,000 compulsory / NAD 200,000 voluntary per 12-month period.

> Do not present volume bands as a legal requirement; label them as internal risk criteria.

---

## 5. Who reviews at each stage

| Stage | Reviewer / decision-maker | Legal basis / notes |
|-------|--------------------------|---------------------|
| **Client onboarding / KYC capture** | The accountable institution itself (e.g. your bank / portal operator) | Must identify client + beneficial owner before establishing a business relationship (FIA s.21) |
| **Enhanced due diligence (high-risk client / PIP)** | **Senior management** of the institution must approve | FIA s.23(2)(a), s.23A |
| **Suspicious transaction / activity reporting** | Reported to the **Financial Intelligence Centre (FIC)** | FIA s.33, s.41 |
| **Supervision & enforcement** | **NAMFISA** (for banks and financial institutions) or the **Centre** directly | NAMFISA is the supervisory body in FIA Schedule 2 |
| **PIP designation** | FIA Schedule 6 — heads of state/government, ministers/deputy ministers, senior politicians, members of parliament, senior government officials, members of governing bodies of political parties, members of local/regional councils, senior management/executives/board members of public-owned enterprises, judicial officers, ambassadors, high-ranking armed forces officers, traditional leaders, religious leaders, senior executives of private entities (prescribed turnover), senior executives of international organisations | — |

### About `ops-admin-mindlabs`
This is **test / seed data** in the application, not a real Namibian regulator.
- In the seeded database it is a `MINDLABS_ADMIN` user bound to the platform-root org as ADMIN.
- In the app’s role mapping it is treated as platform staff (alongside `TPTS_OPS`), so it can read the sandbox activation outbox and decide admin-recovery requests — but it **cannot self-review** its own recovery request (independence / second-person rule).
- It has **no statutory review role** under Namibian law; treat it as an internal test/admin account only.

---

## 6. Record retention — how long rejected applications & KYC records are kept

| Record type | Retention period | Authority |
|-------------|-----------------|-----------|
| CDD / KYC identification & verification records | **At least 5 years** from termination of business relationship or conclusion of transaction | FIA s.27(1)(a)–(b); FinIntel Regs s.18(5) |
| Enhanced due-diligence findings | **At least 5 years** | FIA s.24(2)(d) |
| Suspicious transaction / activity reports (STRs/SARs) | **At least 5 years** from date report made | FIA s.27(1)(c) |
| Cash transaction reports | **At least 5 years** | FIA s.27(1)(b) |
| Electronic transfer records | **At least 5 years** | FIA s.27(1)(b) |
| BIPA beneficial-ownership non-compliance | Entity placed on “inactive list”; may be **deregistered 6 months** after listing | Deputy Minister Sinimbo statement to National Assembly; BIPA operational practice |
| Risk assessments | Periodic, kept up to date | FinIntel Regs s.24 |

> Longer retention applies if a competent authority specifically requests it before the 5-year period expires.

---

## 7. Recommended format checks for your application

| Field | Recommended validation | Example |
|-------|----------------------|---------|
| BIPA registration number | `^([A-Z]{2})?\d{3,10}$` (normalise: strip `/ - . space`, uppercase) | `2017/1055`, `CC202006396` |
| Namibian TIN | `^\d{8}$` | `73486341` |
| Bank branch code | `^\d{5,6}$` | `481972`, `082372` |
| Beneficial ownership threshold | `>= 25%` ownership or voting rights | — |
| Record retention flag | `>= 5 years` from relationship end / transaction date | — |
| Monthly volume | Internal tier only — not a statutory field | — |

---

## 8. Notes for implementation

1. **Treat 25% as the single BO threshold** for both PSP and EMI onboarding flows.
2. **Do not hard-code monthly volume limits as law** — they are internal risk appetite.
3. **`ops-admin-mindlabs` is a local test admin**, not a Namibian government reviewer.
4. **Retain KYC/BO records for 5 years minimum** before any scheduled deletion.
5. **Validate BIPA registration numbers with the optional prefix** (e.g. `CC`, `CY`, `21`) because close corporations and section 21 companies use them.
