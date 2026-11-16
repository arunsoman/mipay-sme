/**
 * The KYC form every org fills in (NA-PSP-BUSINESS v3), as GET /api/kyc/schema/... answers it:
 * the five tabs and their fields exactly as kyc-filed.md (the merchant data capture spec) words
 * them. Fixture data only, shared by the KYC widgets' fixtures (kyc.application, org.onboard-child).
 */

const field = (key: string, label: string, dataType: string, extra: Record<string, unknown> = {}) => ({
  key, label, helpText: null as string | null, dataType, listCode: null as string | null, listParentField: null,
  required: 'ALWAYS', requiredWhen: null as unknown, visibleWhen: null, pattern: null, minLength: null, maxLength: null,
  minValue: null as number | null, maxValue: null as number | null, derivedFrom: null, canonicalTag: null as string | null,
  sensitivity: 'NONE', sensitive: false, ...extra,
});
const help = (helpText: string, extra: Record<string, unknown> = {}) => ({ helpText, ...extra });
const list = (listCode: string, extra: Record<string, unknown> = {}) => ({ listCode, ...extra });

/** The five tab titles, in order. */
export const KYC_V3_TABS = [
  'Business Profile & General Information',
  'Operational & Store Details',
  'Registered & Operational Address',
  'Ultimate Beneficial Owners (UBO) & Key Management',
  'Settlement & Payout Bank Account Details',
];

export const KYC_V3_SCHEMA = {
  profile: { code: 'NA-PSP-BUSINESS-SUPPLIER', country: 'NA', licenceType: 'PSP' },
  schema: { code: 'NA-PSP-BUSINESS', version: 3 },
  sections: [
    { key: 'businessProfile', title: KYC_V3_TABS[0], kind: 'OBJECT', step: 1, minItems: null, maxItems: null, visible: true, fields: [
      field('legalBusinessName', 'Legal Business Name', 'TEXT', help('Exact registered name as listed in legal government tax registries.', { canonicalTag: 'LEGAL_NAME' })),
      field('tradingName', 'Doing Business As (DBA) / Trading Name', 'TEXT', help('Public-facing store or brand name displayed to customers on checkout pages and bank statements.')),
      field('entityType', 'Entity Type / Legal Structure', 'ENUM', { ...list('KYC_ENTITY_TYPE'), ...help('Sole Proprietorship, Partnership, Private Limited / LLC, Public Corporation, or Trust / NGO.') }),
      field('registrationNumber', 'Business Registration / Corporate ID Number', 'TEXT', help('Official registration number (e.g. CIN, Company Registration No.).', { canonicalTag: 'REG_NUMBER' })),
      field('incorporationDate', 'Date of Incorporation / Establishment', 'DATE', help('Date the business was legally formed (DD/MM/YYYY).')),
      field('taxIdentificationNumber', 'Tax Identification Number (TIN / EIN / GSTIN / VAT ID)', 'TEXT', help('Primary tax registration identifier.', { sensitivity: 'SECRET', sensitive: true })),
    ] },
    { key: 'operationalDetails', title: KYC_V3_TABS[1], kind: 'OBJECT', step: 2, minItems: null, maxItems: null, visible: true, fields: [
      field('mcc', 'Merchant Category Code (MCC) / Industry Vertical', 'ENUM', { ...list('MCC'), ...help('4-digit classification code (e.g. Retail, SaaS, Travel, F&B).') }),
      field('websiteOrStorefront', 'Website URL / Mobile App Link / Storefront Details', 'TEXT', help('Digital footprint or physical store location used for products and terms verification.')),
      field('expectedMonthlyVolume', 'Expected Monthly Processing Volume', 'ENUM', { ...list('NA_VOLUME_BAND'), ...help('Estimated monetary volume used to configure initial risk limits and pricing tiers.') }),
      field('averageTicketSize', 'Average Ticket Size / Transaction Value', 'MONEY', help('Typical value of a single consumer transaction.', { minValue: 0 })),
    ] },
    { key: 'addresses', title: KYC_V3_TABS[2], kind: 'LIST', step: 3, minItems: 1, maxItems: 2, visible: true, fields: [
      field('addressType', 'Address', 'ENUM', { ...list('KYC_ADDRESS_TYPE'), ...help('Registered office address (matching official registration documents); add an operational / store address only if it is different.') }),
      field('streetLine', 'Street line', 'TEXT', { sensitivity: 'PII' }),
      field('town', 'City', 'TEXT'),
      field('region', 'State/Province', 'TEXT'),
      field('postalCode', 'Postal Code', 'TEXT'),
      field('country', 'Country', 'COUNTRY', list('COUNTRY')),
    ] },
    { key: 'ubos', title: KYC_V3_TABS[3], kind: 'LIST', step: 4, minItems: 1, maxItems: 20, visible: true, fields: [
      field('fullLegalName', 'Full Legal Name', 'TEXT', help('Complete legal name of Directors, Partners, or UBOs.', { sensitivity: 'PII' })),
      field('dateOfBirth', 'Date of Birth (DOB)', 'DATE', help('Demographic identification.', { sensitivity: 'PII' })),
      field('nationality', 'Nationality', 'COUNTRY', { ...list('COUNTRY'), ...help('Demographic identification.') }),
      field('residentialAddress', 'Residential Address', 'TEXT', help('Current home address of key personnel.', { sensitivity: 'PII' })),
      field('phoneNumber', 'Phone Number', 'PHONE', help('Mobile number. Person 1 is the contact of the administrator the system creates for the org.', { sensitivity: 'PII' })),
      field('emailAddress', 'Email Address', 'EMAIL', help('For Person 1, the administrator’s login name and activation link are sent here.', { sensitivity: 'PII' })),
      field('identificationType', 'Government-Issued Identification Details: Document Type', 'ENUM', { ...list('KYC_ID_DOC_TYPE'), ...help('Passport, National ID, or Driver’s License.') }),
      field('identificationNumber', 'Government-Issued Identification Details: ID Number', 'TEXT', { sensitivity: 'SECRET', sensitive: true }),
      field('identificationExpiryDate', 'Government-Issued Identification Details: Expiry Date', 'DATE', help('Leave empty only for a national ID without an expiry date.', {
        required: 'WHEN', requiredWhen: { not: { field: 'item.identificationType', eq: 'NATIONAL_ID' } } })),
      field('ownershipPercentage', 'Ownership Percentage', 'PERCENT', help('Shareholding or voting rights distribution (≥ 10% to 25% threshold). Enter 0 for directors without a share.', {
        minValue: 0, maxValue: 100, canonicalTag: 'UBO_OWNERSHIP' })),
      field('politicallyExposedPerson', 'Politically Exposed Person (PEP) Declaration', 'BOOLEAN', help('Does the individual or a close associate hold public office?')),
    ] },
    { key: 'bankAccounts', title: KYC_V3_TABS[4], kind: 'LIST', step: 5, minItems: 1, maxItems: 1, visible: true, fields: [
      field('beneficiaryName', 'Beneficiary / Account Holder Name', 'TEXT', help('Must strictly match the Legal Business Name or the Sole Proprietor’s name.')),
      field('bankCode', 'Bank Name', 'ENUM', list('NA_BANK')),
      field('institutionCode', 'Branch / Institution Code', 'TEXT', help('Institution identifier (e.g. IFSC, Sort Code, Routing Number, SWIFT).')),
      field('accountNumber', 'Account Number / IBAN', 'TEXT', help('Destination account for settlement payouts.', { sensitivity: 'SECRET', sensitive: true })),
      field('accountType', 'Account Type', 'ENUM', { ...list('KYC_ACCOUNT_TYPE'), ...help('Checking / Current vs. Savings.') }),
    ] },
  ],
  rules: [] as { code: string; phase: string; validator: string; message: string }[],
  submitRules: [] as { code: string; phase: string; validator: string; message: string }[],
  lists: {
    KYC_ENTITY_TYPE: [
      { value: 'SOLE_PROPRIETOR', label: 'Sole Proprietorship' }, { value: 'PARTNERSHIP', label: 'Partnership' },
      { value: 'PRIVATE_LIMITED', label: 'Private Limited / LLC' }, { value: 'PUBLIC_CORPORATION', label: 'Public Corporation' },
      { value: 'TRUST_NGO', label: 'Trust / NGO' },
    ],
    MCC: [{ value: '5411', label: 'Grocery stores, supermarkets' }, { value: '5199', label: 'Nondurable goods (wholesale)' },
      { value: '5812', label: 'Eating places, restaurants' }],
    NA_VOLUME_BAND: [{ value: 'BELOW_50K', label: 'Under N$50,000 per month' }, { value: '250K_1M', label: 'N$250,000 to N$1 million per month' }],
    KYC_ADDRESS_TYPE: [{ value: 'REGISTERED', label: 'Registered Office Address' }, { value: 'OPERATING', label: 'Operational / Store Address' }],
    COUNTRY: [{ value: 'NA', label: 'Namibia' }, { value: 'ZA', label: 'South Africa' }, { value: 'BW', label: 'Botswana' }],
    KYC_ID_DOC_TYPE: [{ value: 'PASSPORT', label: 'Passport' }, { value: 'NATIONAL_ID', label: 'National ID' },
      { value: 'DRIVING_LICENCE', label: 'Driver’s License' }],
    NA_BANK: [{ value: 'FNB_NAMIBIA', label: 'First National Bank Namibia' }, { value: 'BANK_WINDHOEK', label: 'Bank Windhoek' }],
    KYC_ACCOUNT_TYPE: [{ value: 'CURRENT', label: 'Checking / Current' }, { value: 'SAVINGS', label: 'Savings' }],
  } as Record<string, { value: string; label: string }[]>,
  params: { uboThresholdPct: 25 },
};

/** A complete, valid document for the form above. */
export const KYC_V3_DOC = {
  businessProfile: { legalBusinessName: 'Kalahari Supplies (Pty) Ltd', tradingName: 'Kalahari Supplies',
    entityType: 'PRIVATE_LIMITED', registrationNumber: '2019/0412', incorporationDate: '2019-03-14',
    taxIdentificationNumber: '10412233' },
  operationalDetails: { mcc: '5199', websiteOrStorefront: 'https://kalahari.example.na', expectedMonthlyVolume: '250K_1M',
    averageTicketSize: 5400 },
  addresses: [{ addressType: 'REGISTERED', streetLine: '12 Sam Nujoma Dr', town: 'Windhoek', region: 'Khomas',
    postalCode: '10005', country: 'NA' }],
  ubos: [{ fullLegalName: 'Ndapewa Shikongo', dateOfBirth: '1984-07-02', nationality: 'NA',
    residentialAddress: '7 Nelson Mandela Ave, Windhoek', phoneNumber: '+264812345678', emailAddress: 'ndapewa@kalahari.example.na',
    identificationType: 'NATIONAL_ID', identificationNumber: '84070200123', identificationExpiryDate: null,
    ownershipPercentage: 60, politicallyExposedPerson: false }],
  bankAccounts: [{ beneficiaryName: 'Kalahari Supplies (Pty) Ltd', bankCode: 'FNB_NAMIBIA', institutionCode: '280172',
    accountNumber: '62123456789', accountType: 'CURRENT' }],
};
