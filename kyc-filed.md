# Merchant Data Capture API & App Forms

October 2026

## 1. Business Profile & General Information

- **Legal Business Name:** Exact registered name as listed in legal government tax registries.
- **Doing Business As (DBA) / Trading Name:** Public-facing store or brand name displayed to customers on checkout pages and bank statements.
- **Entity Type / Legal Structure:** Selection among Sole Proprietorship, Partnership, Private Limited / LLC, Public Corporation, or Trust/NGO.
- **Business Registration / Corporate ID Number:** Official registration number (e.g., CIN, Company Registration No.).
- **Date of Incorporation / Establishment:** Date the business was legally formed (DD/MM/YYYY).
- **Tax Identification Number (TIN / EIN / GSTIN / VAT ID):** Primary tax registration identifier.

## 2. Operational & Store Details

- **Merchant Category Code (MCC) / Industry Vertical:** 4-digit classification code (e.g., Retail, SaaS, Travel, F&B).
- **Website URL / Mobile App Link / Storefront Details:** Digital footprint or physical store location used for products and terms verification.
- **Expected Monthly Processing Volume:** Estimated monetary volume used to configure initial risk limits and pricing tiers.
- **Average Ticket Size / Transaction Value:** Typical value of a single consumer transaction.

## 3. Registered & Operational Address

- **Registered Office Address:** Street line, City, State/Province, Postal Code, Country (matching official registration documents).
- **Operational / Store Address:** Physical location where business operations or fulfillment take place (if different from registered address).

## 4. Ultimate Beneficial Owners (UBO) & Key Management

- **Full Legal Name:** Complete legal name of Directors, Partners, or UBOs.
- **Date of Birth (DOB) & Nationality:** Demographic identification.
- **Residential Address:** Current home address of key personnel.
- **Government-Issued Identification Details:** Document type (Passport, National ID, Driver's License), ID Number, and Expiry Date.
- **Ownership Percentage:** Shareholding or voting rights distribution (≥ 10% to 25% threshold).
- **Politically Exposed Person (PEP) Declaration:** Boolean check (Yes/No) regarding whether the individual or close associates hold public office.

## 5. Settlement & Payout Bank Account Details

- **Beneficiary / Account Holder Name:** Must strictly match the Legal Business Name or Sole Proprietor's name.
- **Bank Name & Branch / Institution Code:** Institution identifier (e.g., IFSC, Sort Code, Routing Number, SWIFT).
- **Account Number / IBAN:** Destination account for settlement payouts.
- **Account Type:** Checking / Current vs. Savings.

---

# How MiPay implements this form

## The five tabs

Every KYC screen shows the five sections above as five tabs, using the exact titles in this order:

| # | Tab title | Section key |
|---|---|---|
| 1 | Business Profile & General Information | `businessProfile` |
| 2 | Operational & Store Details | `operationalDetails` |
| 3 | Registered & Operational Address | `addresses` |
| 4 | Ultimate Beneficial Owners (UBO) & Key Management | `ubos` |
| 5 | Settlement & Payout Bank Account Details | `bankAccounts` |

Fields show their names exactly as written above. Asides such as "(DOB)" and "(TIN / EIN / GSTIN / VAT ID)" are part of the name. When one field above needs several inputs, the inputs are named after its parts:

| Field in this document | Inputs on the form |
|---|---|
| Registered Office Address / Operational / Store Address | One address card per kind. The card is titled **Registered Office Address** or **Operational / Store Address** and has Street line, City, State/Province, Postal Code, Country |
| Date of Birth (DOB) & Nationality | Date of Birth (DOB) · Nationality |
| Government-Issued Identification Details | Government-Issued Identification Details: Document Type · …: ID Number · …: Expiry Date |
| Bank Name & Branch / Institution Code | Bank Name · Branch / Institution Code |

Tab 4 also has **Phone Number** and **Email Address** for each person. These are not in the list above. They are there because Person 1's phone and email are the contact details of the administrator that the system creates (see below).

The widgets that show the tabs:

- `org.onboard-child` (onboarding)
- `kyc.application` (edit and review)

The portals' onboarding pages use the same form too (`shared/kyc/onboard-child.ts`). All of them draw it with the shared KYC editor (`shared/kyc/kyc-schema-editor.ts` and `kyc-progress.ts`).

## Onboarding is one step: creating the KYC is the onboarding

Onboarding an organization used to take two steps. First the org and its administrator were created (`POST /api/orgs/me/children`), and then its KYC was filled in and submitted. **That two-step flow is gone, and the endpoint is retired.**

Onboarding is now a single action:

1. The onboarding org picks the new organization's type on the first tab and fills in the five tabs. Nothing exists on the server yet.
2. **Submit for review** sends the whole KYC document in one call: `POST /api/orgs/me/children/with-kyc` (the widget command `org.children.onboard`).
3. In one transaction, that call creates:
   - the organization
   - its administrator
   - the KYC application, saved and submitted for review

   If anything is refused (a KYC problem, a rank rule, a missing right), everything is rolled back and nothing is left half-created.
4. The org goes live when the KYC workflow approves the application.

## The administrator is created by the system

The onboarder never types a login name. The system builds the administrator's **login name** from the business:

1. **The Legal Business Name** in lower case, with only the letters a-z and digits 0-9 kept. Accents are dropped first.
   For example, "Namib Fresh (Pty) Ltd" → `namibfreshptyltd`.
2. **If another business already has that name** (the login is taken), the login is the business name followed by the **Business Registration / Corporate ID Number**, folded the same way.
   For example, "Namib Fresh (Pty) Ltd" with registration number "CC/2019/04512" → `namibfreshptyltd-cc201904512`.
   Registration numbers are unique on the platform (rule `UNIQUE_REGISTRATION`), so this login is unique too.
3. A login is at most 32 characters, so the name part is shortened to make room for the registration number. In the rare case that even step 2's login is taken, `-2`, `-3`… is added.

The administrator's **contact details** are those of UBO Person 1 on tab 4: Full Legal Name, Phone Number and Email Address. The activation link is sent to that email once the KYC is approved.

The rule is implemented in `UsernamePolicy.forBusiness(legalName, registrationNumber, …)` and used by `OrgOnboardingService.onboardWithKyc`.
