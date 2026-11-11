package com.mipay.sme.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.domain.kyc.KycField;
import com.mipay.sme.domain.kyc.KycList;
import com.mipay.sme.domain.kyc.KycListItem;
import com.mipay.sme.domain.kyc.KycProfile;
import com.mipay.sme.domain.kyc.KycRule;
import com.mipay.sme.domain.kyc.KycSchemaVersion;
import com.mipay.sme.domain.kyc.KycSection;
import com.mipay.sme.repository.KycFieldRepository;
import com.mipay.sme.repository.KycListItemRepository;
import com.mipay.sme.repository.KycListRepository;
import com.mipay.sme.repository.KycProfileRepository;
import com.mipay.sme.repository.KycRuleRepository;
import com.mipay.sme.repository.KycSchemaVersionRepository;
import com.mipay.sme.repository.KycSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Seeds the KYC master exactly like V8/V9 do on Postgres (§5). Runs only when
 * Flyway is off (the H2 test profile): migrations never run there, so the same
 * dataset is applied through JPA. Idempotent: skips when NA-PSP-BUSINESS v1
 * already exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
@Order(0)
public class KycMasterSeed implements ApplicationRunner {

    private final KycListRepository lists;
    private final KycListItemRepository items;
    private final KycSchemaVersionRepository schemas;
    private final KycSectionRepository sections;
    private final KycFieldRepository fields;
    private final KycRuleRepository rules;
    private final KycProfileRepository profiles;
    private final ObjectMapper om = new ObjectMapper();
    private final List<FieldBuilder> pendingFields = new java.util.ArrayList<>();

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (schemas.findByCodeAndVersion("NA-PSP-BUSINESS", 1).isPresent()) return;
        seed();
        log.info("KYC master seed complete: 18 lists, 1 schema, 7 sections, 48 fields, 12 rules, 5 profiles");
    }

    void seed() {
        list("NA_ENTITY_TYPE", null, "Namibian entity types (Companies Act 28 of 2004 / Close Corporations Act 26 of 1988)",
                "SOLE_PROPRIETOR|Sole proprietor", "PARTNERSHIP|Partnership",
                "CLOSE_CORPORATION|Close corporation (CC)", "PRIVATE_COMPANY|Private company ((Pty) Ltd)",
                "PUBLIC_COMPANY|Public company (Ltd)", "SECTION_21_COMPANY|Section 21 (not for gain)",
                "TRUST|Trust", "STATE_OWNED_ENTERPRISE|State-owned enterprise");
        list("COUNTRY", null, "ISO 3166-1 alpha-2 selectable countries",
                "NA|Namibia", "ZA|South Africa", "BW|Botswana", "SZ|Eswatini", "LS|Lesotho",
                "ZM|Zambia", "ZW|Zimbabwe", "MZ|Mozambique", "AO|Angola", "OTHER|Other");
        list("NA_REGION", "NA", "Namibian regions",
                "ERONGO|Erongo", "HARDAP|Hardap", "KHARAS|//Kharas", "KAVANGO_EAST|Kavango East",
                "KAVANGO_WEST|Kavango West", "KHOMAS|Khomas", "KUNENE|Kunene", "OHANGWENA|Ohangwena",
                "OMAHEKE|Omaheke", "OMUSATI|Omusati", "OSHANA|Oshana", "OSHIKOTO|Oshikoto",
                "OTJOZONDJUPA|Otjozondjupa", "ZAMBEZI|Zambezi");
        list("ADDRESS_TYPE", null, "Address type",
                "REGISTERED|Registered address", "OPERATING|Operating address", "POSTAL|Postal address");
        list("ID_TYPE", null, "Identity document type",
                "NATIONAL_ID|National ID", "PASSPORT|Passport", "DRIVING_LICENCE|Driving licence",
                "RESIDENCE_PERMIT|Residence permit", "WORK_PERMIT|Work permit");
        list("UBO_ROLE", null, "Beneficial owner / controller role (multi-select)",
                "SHAREHOLDER|Shareholder", "MEMBER|Close corporation member", "PARTNER|Partner",
                "PROPRIETOR|Sole proprietor", "DIRECTOR|Director", "SENIOR_MANAGER|Senior management official",
                "SETTLOR|Settlor", "TRUSTEE|Trustee", "PROTECTOR|Protector", "BENEFICIARY|Beneficiary",
                "NOMINEE|Nominee shareholder / director",
                "NOMINATOR|Nominator (person in whose name the nominee holds)",
                "AUTHORISED_SIGNATORY|Authorised signatory", "CONTROLLING_PERSON|Controlling person");
        list("OWNERSHIP_TYPE", null, "Direct or indirect holding",
                "DIRECT|Direct holding", "INDIRECT|Indirect holding");
        list("CONTROL_BASIS", null, "Basis of control when no owner reaches the 25% threshold (FIA Schedule 5)",
                "SENIOR_MANAGEMENT|Control via senior management official",
                "APPOINTS_BOARD_MAJORITY|Appoints or removes majority of the board",
                "MATERIAL_INFLUENCE|Other material influence",
                "ECONOMIC_BENEFIT|Benefits economically otherwise", "OTHER_CONTROL|Other control basis");
        list("PIP_CATEGORY", null, "Prominent influential person category (FIA Schedule 6)",
                "HEAD_OF_STATE_GOVERNMENT|Head of state / head of government",
                "MINISTER_DEPUTY|Government minister / deputy minister",
                "SENIOR_POLITICIAN|Senior politician", "MEMBER_OF_PARLIAMENT|Member of parliament",
                "SENIOR_GOVERNMENT_OFFICIAL|Senior government official",
                "POLITICAL_PARTY_GOVERNING_BODY|Member of governing body of political party",
                "LOCAL_REGIONAL_COUNCILLOR|Member of local or regional council",
                "PUBLIC_ENTERPRISE_EXECUTIVE|Senior executive of public-owned enterprise",
                "JUDICIAL_OFFICER|Judicial officer", "AMBASSADOR|Ambassador / high-ranking diplomat",
                "SENIOR_MILITARY_OFFICER|High-ranking officer of armed forces",
                "TRADITIONAL_LEADER|Member of Traditional Authority",
                "RELIGIOUS_LEADER|Member of governing body of religious denomination",
                "PRIVATE_SECTOR_SENIOR_EXECUTIVE|Senior executive of company / body corporate",
                "INTERNATIONAL_ORG_EXECUTIVE|Senior executive of international organisation",
                "FOREIGN_PIP|Foreign prominent influential person",
                "FAMILY_MEMBER|Family member of a PIP", "CLOSE_ASSOCIATE|Close associate of a PIP");
        list("NA_BANK", "NA", "Authorised Namibian banking institutions",
                "BANK_WINDHOEK|Bank Windhoek", "FNB_NAMIBIA|First National Bank Namibia",
                "STANDARD_BANK_NA|Standard Bank Namibia", "NEDBANK_NA|Nedbank Namibia",
                "BANK_BIC|Bank BIC Namibia", "LETSHEGO|Letshego Bank Namibia", "FOREIGN|Foreign bank account");
        list("NA_ACCOUNT_TYPE", null, "Namibian bank account type",
                "CURRENT|Current / cheque", "SAVINGS|Savings", "TRANSMISSION|Transmission");
        list("CURRENCY", null, "Currency",
                "NAD|Namibian dollar (N$)", "ZAR|South African rand", "USD|US dollar");
        list("INDUSTRY_VERTICAL", null, "Industry vertical",
                "RETAIL|Retail", "FOOD_BEVERAGE|Food and beverage", "HOSPITALITY|Hospitality",
                "TRANSPORT|Transport", "HEALTH|Health", "EDUCATION|Education", "NON_PROFIT|Non-profit",
                "WHOLESALE_DISTRIBUTION|Wholesale distribution", "PROFESSIONAL_SERVICES|Professional services",
                "MANUFACTURING|Manufacturing", "AGRICULTURE|Agriculture", "CONSTRUCTION|Construction",
                "TELECOMS_UTILITIES|Telecoms and utilities", "GOVERNMENT|Government", "OTHER|Other");
        list("NA_VOLUME_BAND", "NA", "Expected monthly volume band (N$/month; internal risk tier, FIA s.20A)",
                "BELOW_50K|Under N$50,000 per month", "50K_250K|N$50,000 to N$250,000 per month",
                "250K_1M|N$250,000 to N$1 million per month", "1M_5M|N$1 million to N$5 million per month",
                "OVER_5M|Over N$5 million per month");
        list("TRADING_ROLE", null, "Trading role",
                "MANUFACTURER|Manufacturer", "IMPORTER|Importer", "WHOLESALER|Wholesaler",
                "DISTRIBUTOR|Distributor", "RETAILER|Retailer", "SERVICE_PROVIDER|Service provider");
        list("TURNOVER_BAND", null, "Annual turnover band (N$/year)",
                "UNDER_1M|Under N$1,000,000 per year", "1M_10M|N$1,000,000 to N$10,000,000 per year",
                "10M_50M|N$10,000,000 to N$50,000,000 per year", "OVER_50M|Over N$50,000,000 per year");
        list("MCC", null, "ISO 18245 merchant category codes (subset)",
                "5411|Grocery stores, supermarkets", "5812|Eating places, restaurants",
                "5813|Drinking places", "5541|Service stations", "5311|Department stores",
                "5651|Clothing stores", "5999|Specialty retail stores", "4121|Taxicabs and limousines",
                "7011|Hotels and lodging", "8062|Hospitals", "8211|Schools",
                "8661|Religious organizations", "5065|Distributors of electronic parts",
                "5199|Nondurable goods (wholesale)", "4829|Money transfer",
                "6012|Financial institutions", "7995|Betting / casinos",
                "5968|Direct marketers - outbound telesales");

        // branch codes per bank (sandbox list dated 1 June 2016); attributes.branchCode
        branch("WINDHOEK", "Windhoek", "BANK_WINDHOEK", "481972", 1);
        branch("MAERUA_MALL", "Maerua Mall", "BANK_WINDHOEK", "483872", 2);
        branch("SWAKOPMUND", "Swakopmund", "BANK_WINDHOEK", "481772", 3);
        branch("KATIMA_MULILO", "Katima Mulilo", "BANK_WINDHOEK", "482273", 4);
        branch("WINDHOEK_MAIN", "Windhoek Main", "FNB_NAMIBIA", "280172", 5);
        branch("FNB_MAERUA", "Maerua Mall", "FNB_NAMIBIA", "282273", 6);
        branch("FNB_SWAKOPMUND", "Swakopmund", "FNB_NAMIBIA", "280472", 7);
        branch("SMB_WINDHOEK", "Windhoek", "STANDARD_BANK_NA", "082372", 8);
        branch("SMB_SWAKOPMUND", "Swakopmund", "STANDARD_BANK_NA", "082172", 9);
        branch("NED_WINDHOEK_SOUTH", "Windhoek South", "NEDBANK_NA", "461629", 10);
        branch("NED_SWAKOPMUND", "Swakopmund", "NEDBANK_NA", "461052", 11);
        branch("BW_UNIVERSAL", "Bank Windhoek (universal)", "BANK_WINDHOEK", "482872", 20);
        branch("FNB_UNIVERSAL", "FNB Namibia (universal)", "FNB_NAMIBIA", "282672", 21);
        branch("SMB_UNIVERSAL", "Standard Bank Namibia (universal)", "STANDARD_BANK_NA", "087373", 22);
        // MCC attributes vertical+risk
        mcc("5411", "RETAIL", "LOW"); mcc("5812", "FOOD_BEVERAGE", "LOW"); mcc("5813", "FOOD_BEVERAGE", "MEDIUM");
        mcc("5541", "RETAIL", "LOW"); mcc("5311", "RETAIL", "LOW"); mcc("5651", "RETAIL", "LOW");
        mcc("5999", "RETAIL", "MEDIUM"); mcc("4121", "TRANSPORT", "MEDIUM"); mcc("7011", "HOSPITALITY", "LOW");
        mcc("8062", "HEALTH", "LOW"); mcc("8211", "EDUCATION", "LOW"); mcc("8661", "NON_PROFIT", "LOW");
        mcc("5065", "WHOLESALE_DISTRIBUTION", "LOW"); mcc("5199", "WHOLESALE_DISTRIBUTION", "MEDIUM");
        mcc("4829", "PROFESSIONAL_SERVICES", "HIGH"); mcc("6012", "PROFESSIONAL_SERVICES", "HIGH");
        mcc("7995", "OTHER", "HIGH"); mcc("5968", "OTHER", "MEDIUM");

        // ---------------------------------------------------------------- schema
        KycSchemaVersion sv = new KycSchemaVersion();
        sv.setCode("NA-PSP-BUSINESS");
        sv.setVersion(1);
        sv.setStatus(KycSchemaVersion.Status.PUBLISHED);
        sv.setPublishedAt(Instant.now());
        schemas.save(sv);

        KycSection businessProfile = section(sv, "businessProfile", "Business", 1, KycSection.Kind.OBJECT, null, null, 10);
        KycSection operational = section(sv, "operationalDetails", "Operations", 2, KycSection.Kind.OBJECT, null, null, 20);
        KycSection addresses = section(sv, "addresses", "Addresses", 3, KycSection.Kind.LIST, 1, 5, 30);
        KycSection ubos = section(sv, "ubos", "Owners and controllers", 4, KycSection.Kind.LIST, 1, 20, 40);
        KycSection bankAccounts = section(sv, "bankAccounts", "Bank account", 5, KycSection.Kind.LIST, 1, 3, 50);
        section(sv, "review", "Review and submit", 6, KycSection.Kind.OBJECT, null, null, 60);
        section(sv, "documents", "Documents", 7, KycSection.Kind.DOCUMENTS, 0, 0, 70);

        // ---------------------------------------------------------------- fields
        // businessProfile
        fld(businessProfile, "entityType", "Entity type", 10).help(
                        "Firms must register at BIPA; the entity type drives the rest of the form.")
                .type(KycField.DataType.ENUM, "NA_ENTITY_TYPE").req(KycField.Required.ALWAYS);
        fld(businessProfile, "legalBusinessName", "Legal business name", 20)
                .help("Exactly as on the BIPA registration certificate.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS)
                .len(2, 200).tag("LEGAL_NAME");
        fld(businessProfile, "tradingName", "Trading name (if any)", 30)
                .type(KycField.DataType.TEXT, null).req(KycField.Required.NEVER).len(null, 200);
        fld(businessProfile, "registrationNumber", "BIPA / CC registration number", 40)
                .help("Stored as typed, e.g. CC/2020/06396 or 2017/1055.")
                .type(KycField.DataType.TEXT, null)
                .reqWhen("WHEN", not("businessProfile.entityType", "SOLE_PROPRIETOR"))
                .pattern("^([A-Z]{2})?\\d{3,10}$").normalize(KycField.Normalize.PUNCT).len(3, 32)
                .tag("REG_NUMBER");
        fld(businessProfile, "incorporationDate", "Date of incorporation", 50)
                .help("Unless sole proprietor.")
                .type(KycField.DataType.DATE, null)
                .reqWhen("WHEN", not("businessProfile.entityType", "SOLE_PROPRIETOR"))
                .dateRule(KycField.DateRule.PAST);
        fld(businessProfile, "taxIdentificationNumber", "NamRA tax identification number (TIN)", 60)
                .help("8 digits; a 7-digit legacy reference is stored with a leading 0.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS)
                .pattern("^\\d{8}$").len(7, 8).tag("TAX_ID").sens(KycField.Sensitivity.SECRET, KycField.Mask.LAST4);
        fld(businessProfile, "vatNumber", "VAT registration number (if any)", 70)
                .help("VAT registration is compulsory from N$1,000,000 in taxable supplies a year.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.NEVER).len(null, 32);
        fld(businessProfile, "countryOfRegistration", "Country of registration", 80)
                .type(KycField.DataType.COUNTRY, "COUNTRY").req(KycField.Required.ALWAYS).len(null, 2);

        // operationalDetails (fields depend on org type)
        String ORG_MERCHANT = "{\"context\":\"orgType\",\"in\":[\"MERCHANT\"]}";
        fld(operational, "mcc", "Merchant category code (MCC)", 10)
                .help("ISO 18245 code describing the main trade. Searchable.")
                .type(KycField.DataType.ENUM, "MCC").reqWhen("WHEN", ORG_MERCHANT).len(4, 4).tag("MCC");
        fld(operational, "industryVertical", "Industry vertical", 20)
                .help("Derived from the MCC where one is shown; otherwise choose.")
                .type(KycField.DataType.ENUM, "INDUSTRY_VERTICAL").reqWhen("WHEN",
                        "{\"any\":[{\"context\":\"orgType\",\"in\":[\"MERCHANT\",\"SUPPLIER\"]},"
                                + "{\"not\":{\"field\":\"operationalDetails.mcc\",\"exists\":true}}]}")
                .derivedFrom("mcc");
        fld(operational, "tradingRole", "Trading role", 30)
                .type(KycField.DataType.ENUM, "TRADING_ROLE").reqWhen("WHEN",
                        "{\"context\":\"orgType\",\"in\":[\"MANUFACTURER\",\"DISTRIBUTOR\",\"SUPPLIER\"]}");
        fld(operational, "expectedMonthlyVolume", "Expected monthly volume band", 40)
                .help("Internal risk tiering only; there is no legal threshold.")
                .type(KycField.DataType.ENUM, "NA_VOLUME_BAND").reqWhen("WHEN",
                        "{\"context\":\"orgType\",\"in\":[\"MERCHANT\",\"SUPPLIER\"]}")
                .tag("EXPECTED_MONTHLY_VOLUME");
        fld(operational, "averageTicketSize", "Average transaction size (N$)", 50)
                .type(KycField.DataType.MONEY, null).reqWhen("WHEN", ORG_MERCHANT)
                .len(0, 14).range("0", null);
        fld(operational, "annualTurnover", "Annual turnover band", 60)
                .type(KycField.DataType.ENUM, "TURNOVER_BAND").reqWhen("WHEN",
                        "{\"context\":\"orgType\",\"in\":[\"MANUFACTURER\",\"DISTRIBUTOR\"]}");
        fld(operational, "websiteUrl", "Website URL (if any)", 70)
                .help("https only.")
                .type(KycField.DataType.URL, null).req(KycField.Required.NEVER).len(null, 300);
        fld(operational, "mobileAppLink", "Mobile app link (if any)", 80)
                .help("https only.")
                .type(KycField.DataType.URL, null).req(KycField.Required.NEVER).len(null, 300);
        fld(operational, "storefrontDetails", "Storefront details", 90)
                .help("Where customers find the business. Shown for merchants.")
                .type(KycField.DataType.TEXT, null).reqWhen("WHEN", ORG_MERCHANT).len(null, 500);

        // addresses
        fld(addresses, "addressType", "Address type", 10)
                .help("Exactly one registered address.")
                .type(KycField.DataType.ENUM, "ADDRESS_TYPE").req(KycField.Required.ALWAYS);
        fld(addresses, "sameAsRegistered", "Same as registered address", 20)
                .help("Shown for non-registered addresses; copies the fields on save.")
                .type(KycField.DataType.BOOLEAN, null).req(KycField.Required.NEVER)
                .visWhen(not("item.addressType", "REGISTERED"));
        fld(addresses, "streetLine", "Street address", 30)
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS)
                .sens(KycField.Sensitivity.PII, null).len(null, 200);
        fld(addresses, "town", "City / town", 40)
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS).len(null, 100);
        fld(addresses, "region", "Region", 50)
                .help("Required for Namibian addresses.")
                .type(KycField.DataType.ENUM, "NA_REGION").reqWhen("WHEN", eq("item.country", "NA"));
        fld(addresses, "postalCode", "Postal code", 60)
                .help("Optional in Namibia.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.NEVER).len(null, 10);
        fld(addresses, "country", "Country", 70)
                .type(KycField.DataType.COUNTRY, "COUNTRY").req(KycField.Required.ALWAYS).len(null, 2);

        // ubos
        String ownerRoles = "{\"any\":[{\"field\":\"item.roles\",\"contains\":\"SHAREHOLDER\"},"
                + "{\"field\":\"item.roles\",\"contains\":\"MEMBER\"},{\"field\":\"item.roles\",\"contains\":\"PARTNER\"},"
                + "{\"field\":\"item.roles\",\"contains\":\"PROPRIETOR\"}]}";
        fld(ubos, "fullLegalName", "Full legal name", 10)
                .help("As on the identity document.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS)
                .len(2, 200).tag("UBO_NAME").sens(KycField.Sensitivity.PII, null);
        fld(ubos, "roles", "Roles", 20)
                .help("All roles this person holds in the entity.")
                .type(KycField.DataType.MULTI_ENUM, "UBO_ROLE").req(KycField.Required.ALWAYS);
        fld(ubos, "dateOfBirth", "Date of birth", 30)
                .type(KycField.DataType.DATE, null).req(KycField.Required.ALWAYS)
                .dateRule(KycField.DateRule.PAST).tag("UBO_DOB").sens(KycField.Sensitivity.PII, KycField.Mask.HIDE);
        fld(ubos, "nationality", "Nationality", 40)
                .type(KycField.DataType.COUNTRY, "COUNTRY").req(KycField.Required.ALWAYS)
                .len(null, 2).tag("UBO_NATIONALITY");
        fld(ubos, "identificationType", "Identity document type", 50)
                .help("National ID only for Namibian nationals.")
                .type(KycField.DataType.ENUM, "ID_TYPE").req(KycField.Required.ALWAYS).tag("UBO_ID_TYPE");
        fld(ubos, "identificationNumber", "Identity number / document number", 60)
                .help("NA national ID: 11 digits matching the date of birth; passports/permits are free-form.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS)
                .pattern("^.{5,32}$").len(5, 32).tag("UBO_ID_NUMBER")
                .sens(KycField.Sensitivity.SECRET, KycField.Mask.LAST4);
        fld(ubos, "identificationExpiryDate", "Identity document expiry date", 70)
                .help("Not for NA national IDs.")
                .type(KycField.DataType.DATE, null)
                .reqWhen("WHEN", not("item.identificationType", "NATIONAL_ID"))
                .dateRule(KycField.DateRule.FUTURE).tag("UBO_ID_EXPIRY");
        fld(ubos, "ownershipType", "Ownership type", 80)
                .help("Direct or indirect. Required with ordinary ownership.")
                .type(KycField.DataType.ENUM, "OWNERSHIP_TYPE").reqWhen("WHEN", ownerRoles);
        fld(ubos, "ownershipPercentage", "Ownership percentage", 90)
                .help("Percent of shares or members interest. Considered against voting rights for the 25% test.")
                .type(KycField.DataType.PERCENT, null).reqWhen("WHEN", ownerRoles)
                .range("0", "100").len(null, 6).tag("UBO_OWNERSHIP");
        fld(ubos, "effectiveOwnershipPercentage", "Effective ownership percentage", 100)
                .help("For indirect holdings: the stake multiplied down the ownership chain; used for the 25% test.")
                .type(KycField.DataType.PERCENT, null)
                .reqWhen("WHEN", eq("item.ownershipType", "INDIRECT"))
                .range("0", "100").len(0, 6);
        fld(ubos, "votingRightsPercentage", "Voting rights percentage", 110)
                .help("The 25% test considers ownership or voting rights.")
                .type(KycField.DataType.PERCENT, null).req(KycField.Required.NEVER)
                .range("0", "100").len(0, 6);
        fld(ubos, "controlBasis", "Basis of control", 120)
                .help("FIA Schedule 5: required for controlling persons and senior managers.")
                .type(KycField.DataType.ENUM, "CONTROL_BASIS").reqWhen("WHEN",
                        "{\"any\":[{\"field\":\"item.roles\",\"contains\":\"CONTROLLING_PERSON\"},"
                                + "{\"field\":\"item.roles\",\"contains\":\"SENIOR_MANAGER\"}]}");
        fld(ubos, "politicallyExposedPerson", "Prominent influential person (PIP)", 130)
                .type(KycField.DataType.BOOLEAN, null).req(KycField.Required.ALWAYS).tag("UBO_PEP");
        fld(ubos, "pipCategory", "PIP category", 140)
                .help("FIA Schedule 6 category; required for a PIP.")
                .type(KycField.DataType.ENUM, "PIP_CATEGORY")
                .reqWhen("WHEN", eq("item.politicallyExposedPerson", "true"));
        fld(ubos, "pipPosition", "PIP position", 150)
                .help("The office the person holds.")
                .type(KycField.DataType.TEXT, null)
                .reqWhen("WHEN", eq("item.politicallyExposedPerson", "true"))
                .len(null, 200);

        // bankAccounts
        fld(bankAccounts, "beneficiaryName", "Account holder", 10)
                .help("Exactly as on the bank confirmation letter.")
                .type(KycField.DataType.TEXT, null).req(KycField.Required.ALWAYS)
                .len(2, 200).tag("BANK_BENEFICIARY");
        fld(bankAccounts, "bankCode", "Bank", 20)
                .type(KycField.DataType.ENUM, "NA_BANK").req(KycField.Required.ALWAYS).tag("BANK_CODE");
        fld(bankAccounts, "branch", "Branch", 30)
                .help("Branch or universal code; the list depends on the selected bank.")
                .type(KycField.DataType.ENUM, "NA_BANK_BRANCH")
                .reqWhen("WHEN", not("item.bankCode", "FOREIGN"))
                .derivedFrom("bankCode").tag("BANK_BRANCH")
                .listParent("bankCode");
        fld(bankAccounts, "accountNumber", "Account number", 40)
                .help("Namibia does not use IBAN.")
                .type(KycField.DataType.TEXT, null)
                .reqWhen("WHEN", not("item.bankCode", "FOREIGN"))
                .pattern("^\\d{6,16}$").len(6, 16).tag("BANK_ACCOUNT_NUMBER")
                .sens(KycField.Sensitivity.SECRET, KycField.Mask.LAST4);
        fld(bankAccounts, "accountType", "Account type", 50)
                .type(KycField.DataType.ENUM, "NA_ACCOUNT_TYPE").req(KycField.Required.ALWAYS);
        fld(bankAccounts, "currency", "Account currency", 60)
                .help("Namibian dollar unless the account is foreign.")
                .type(KycField.DataType.ENUM, "CURRENCY").req(KycField.Required.ALWAYS)
                .visWhen(not("item.bankCode", "FOREIGN")).len(null, 3);
        fld(bankAccounts, "iban", "Foreign account IBAN", 70)
                .help("Shown for foreign bank accounts.")
                .type(KycField.DataType.TEXT, null)
                .reqWhen("WHEN", eq("item.bankCode", "FOREIGN")).len(null, 34);
        fld(bankAccounts, "swiftBic", "Foreign bank SWIFT-BIC", 80)
                .help("Shown for foreign bank accounts.")
                .type(KycField.DataType.TEXT, null)
                .reqWhen("WHEN", eq("item.bankCode", "FOREIGN"))
                .pattern("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$").len(8, 11);
        fld(bankAccounts, "foreignBankName", "Foreign bank name", 90)
                .help("Shown for foreign bank accounts.")
                .type(KycField.DataType.TEXT, null)
                .reqWhen("WHEN", eq("item.bankCode", "FOREIGN")).len(null, 200);
        flushFields();

        // ---------------------------------------------------------------- rules (§3.3)
        rule(sv, "REG_ADDRESS_EXACT_ONE", KycRule.Phase.SUBMIT, "LIST_COUNT",
                "{\"section\":\"addresses\",\"field\":\"addressType\",\"value\":[\"REGISTERED\"],\"min\":1,\"max\":1}",
                "Exactly one registered address is required.");
        rule(sv, "OWNERSHIP_SUM_MAX", KycRule.Phase.SUBMIT, "SUM_MAX",
                "{\"section\":\"ubos\",\"field\":\"ownershipPercentage\",\"max\":100}",
                "The combined ownership percentages cannot exceed 100%.");
        rule(sv, "UBO_THRESHOLD", KycRule.Phase.SUBMIT, "ANY_GTE",
                "{\"section\":\"ubos\",\"fields\":[\"ownershipPercentage\",\"effectiveOwnershipPercentage\",\"votingRightsPercentage\"],"
                        + "\"thresholdPct\":\"@uboThresholdPct\",\"controlBasis\":\"controlBasis\"}",
                "Add a beneficial owner with at least 25% ownership or voting rights, or declare the control basis (FIA Schedule 5).");
        rule(sv, "SOLE_PROPRIETOR_SINGLE_OWNER", KycRule.Phase.SUBMIT, "LIST_COUNT",
                "{\"section\":\"ubos\",\"field\":\"roles\",\"value\":[\"PROPRIETOR\"],\"min\":1,\"max\":1,"
                        + "\"whenContext\":{\"field\":\"businessProfile.entityType\",\"eq\":\"SOLE_PROPRIETOR\"}}",
                "A sole proprietor is the single owner of the business.");
        rule(sv, "SOLE_PROPRIETOR_HUNDRED_PCT", KycRule.Phase.SUBMIT, "VALUE_EQ",
                "{\"section\":\"ubos\",\"field\":\"ownershipPercentage\",\"eq\":100,\"whenContext\":{\"field\":\"businessProfile.entityType\",\"eq\":\"SOLE_PROPRIETOR\"}}",
                "A sole proprietor holds 100% of the business.");
        rule(sv, "TRUST_TRUSTEE", KycRule.Phase.SUBMIT, "ROLE_PRESENT",
                "{\"section\":\"ubos\",\"field\":\"roles\",\"values\":[\"TRUSTEE\"],\"whenContext\":{\"field\":\"businessProfile.entityType\",\"eq\":\"TRUST\"}}",
                "Trusts must name the settlor, every trustee, any protector and the beneficiaries; at least one trustee is required.");
        rule(sv, "OWNER_MIN_AGE", KycRule.Phase.SUBMIT, "MIN_AGE",
                "{\"section\":\"ubos\",\"field\":\"dateOfBirth\",\"minAge\":\"@minOwnerAge\"}",
                "Every named owner must be at least the minimum age.");
        rule(sv, "NA_ID_MATCHES_DOB", KycRule.Phase.SUBMIT, "ID_MATCHES_DOB",
                "{\"section\":\"ubos\",\"idField\":\"identificationNumber\",\"dobField\":\"dateOfBirth\",\"typeField\":\"identificationType\",\"idType\":\"NATIONAL_ID\"}",
                "A NA national ID must match the date of birth (first six digits = YYMMDD).");
        rule(sv, "BENEFICIARY_NAME_MATCH", KycRule.Phase.SUBMIT, "NAME_MATCH",
                "{\"section\":\"bankAccounts\",\"nameField\":\"beneficiaryName\",\"against\":[\"businessProfile.legalBusinessName\",\"businessProfile.tradingName\"]}",
                "The account holder should match the legal or trading name (warning).");
        rule(sv, "UNIQUE_REGISTRATION", KycRule.Phase.SUBMIT, "UNIQUE_IN_PLATFORM",
                "{\"section\":\"businessProfile\",\"field\":\"registrationNumber\",\"tag\":\"REG_NUMBER\",\"normalize\":\"PUNCT\",\"severity\":\"ERROR\"}",
                "This registration number is already used by another active organization.");
        rule(sv, "UNIQUE_TIN", KycRule.Phase.SUBMIT, "UNIQUE_IN_PLATFORM",
                "{\"section\":\"businessProfile\",\"field\":\"taxIdentificationNumber\",\"tag\":\"TAX_ID\",\"severity\":\"ERROR\"}",
                "This tax identification number is already used by another active organization.");
        rule(sv, "OWNER_ID_UNIQUE", KycRule.Phase.SUBMIT, "UNIQUE_IN_PLATFORM",
                "{\"section\":\"ubos\",\"field\":\"identificationNumber\",\"tag\":\"UBO_ID_NUMBER\",\"severity\":\"WARNING\"}",
                "This identity number already appears in the platform (warning).");

        // ---------------------------------------------------------------- profiles (one per org type)
        String workflowConfig = ("{\"approvalRequired\":true,\"entryCheck\":\"OPTIONAL\","
                + "\"verification\":[\"IDENTITY\",\"SANCTIONS\",\"PEP\"],"
                + "\"stages\":[{\"key\":\"ops-review\",\"actor\":{\"relation\":\"PLATFORM\",\"function\":\"OPS_REVIEWER\"},"
                + "\"outcomes\":[\"APPROVE\",\"RETURN\",\"REJECT\",\"BLOCK\"]}],"
                + "\"contributors\":[\"high-risk-or-pep\"]}");
        for (String orgType : List.of("MERCHANT", "SUPPLIER", "MANUFACTURER", "DISTRIBUTOR", "LENDER")) {
            KycProfile p = new KycProfile();
            p.setCode("NA-PSP-BUSINESS-" + orgType);
            p.setCountry("NA");
            p.setLicenceType("PSP");
            p.setSubjectType("BUSINESS");
            p.setEntityType(null);
            p.setOrgType(orgType);
            p.setSchemaVersionId(sv.getId());
            p.setWorkflowKey("configurable");
            p.setWorkflowVersion(1);
            p.setWorkflowConfig(workflowConfig);
            p.setCollectionProvider("native-form");
            p.setCheckProviders("{\"IDENTITY\":\"simulated\",\"SANCTIONS\":\"simulated\",\"PEP\":\"simulated\"}");
            p.setRiskScorer("default");
            p.setParams("{\"uboThresholdPct\":25,\"minOwnerAge\":18,"
                    + "\"reviewMonths\":{\"LOW\":36,\"MEDIUM\":24,\"HIGH\":12},\"checkTimeoutHours\":24}");
            p.setUiEnabled(true);
            p.setActive(true);
            p.setEffectiveFrom(Instant.now());
            profiles.save(p);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String not(String field, String val) {
        return "{\"not\":{\"field\":\"" + field + "\",\"eq\":\"" + val + "\"}}";
    }

    private static String eq(String field, String val) {
        return "{\"field\":\"" + field + "\",\"eq\":\"" + val + "\"}";
    }

    private void list(String code, String country, String description, String... pairs) {
        KycList l = new KycList();
        l.setCode(code);
        l.setCountry(country);
        l.setDescription(description);
        lists.save(l);
        int sort = 1;
        for (String pair : pairs) {
            int at = pair.indexOf('|');
            item(code, pair.substring(0, at), pair.substring(at + 1), null, null, sort++);
        }
    }

    private void item(String code, String value, String label, String parent, Map<String, Object> attrs, int sort) {
        KycListItem i = new KycListItem();
        i.setListCode(code);
        i.setValue(value);
        i.setLabel(label);
        i.setParentValue(parent);
        i.setAttributes(attrs == null ? null : json(attrs));
        i.setSort(sort);
        i.setActive(true);
        items.save(i);
    }

    private void branch(String value, String label, String bank, String branchCode, int sort) {
        item("NA_BANK_BRANCH", value, label, bank, Map.of("branchCode", branchCode), sort);
    }

    private void mcc(String value, String vertical, String risk) {
        KycListItem i = items.findByListCodeAndValue("MCC", value).orElseGet(() -> {
            KycListItem it = new KycListItem();
            it.setListCode("MCC");
            it.setValue(value);
            it.setLabel(value + " code");
            it.setSort(0);
            it.setActive(true);
            items.save(it);
            return it;
        });
        i.setAttributes(json(Map.of("vertical", vertical, "risk", risk)));
        items.save(i);
    }

    private KycSection section(KycSchemaVersion sv, String key, String title, int step,
                               KycSection.Kind kind, Integer min, Integer max, int sort) {
        KycSection s = new KycSection();
        s.setSchemaVersionId(sv.getId());
        s.setKey(key);
        s.setTitle(title);
        s.setStep(step);
        s.setKind(kind);
        s.setMinItems(min);
        s.setMaxItems(max);
        s.setSort(sort);
        sections.save(s);
        return s;
    }

    private FieldBuilder fld(KycSection section, String key, String label, int sort) {
        FieldBuilder b = new FieldBuilder(section, key, label, sort);
        pendingFields.add(b);
        return b;
    }

    /** Persist every field built so far (schema + sections must be saved first). */
    private void flushFields() {
        for (FieldBuilder b : List.copyOf(pendingFields)) b.save();
        pendingFields.clear();
    }

    /** Builder so every field reads declaratively instead of counting nulls. */
    private class FieldBuilder {
        private final KycField f = new KycField();

        FieldBuilder(KycSection section, String key, String label, int sort) {
            f.setSectionId(section.getId());
            f.setKey(key);
            f.setLabel(label);
            f.setSort(sort);
            f.setDataType(KycField.DataType.TEXT);
            f.setRequired(KycField.Required.ALWAYS);
            f.setSensitivity(KycField.Sensitivity.NONE);
        }

        FieldBuilder help(String help) {
            f.setHelpText(help);
            return this;
        }

        FieldBuilder type(KycField.DataType type, String listCode) {
            f.setDataType(type);
            f.setListCode(listCode);
            return this;
        }

        FieldBuilder req(KycField.Required required) {
            f.setRequired(required);
            return this;
        }

        FieldBuilder reqWhen(String required, String whenJson) {
            f.setRequired(KycField.Required.valueOf(required));
            f.setRequiredWhen(whenJson);
            return this;
        }

        FieldBuilder visWhen(String whenJson) {
            f.setVisibleWhen(whenJson);
            return this;
        }

        FieldBuilder pattern(String pattern) {
            f.setPattern(pattern);
            return this;
        }

        FieldBuilder normalize(KycField.Normalize normalize) {
            f.setNormalize(normalize);
            return this;
        }

        FieldBuilder len(Integer min, Integer max) {
            f.setMinLen(min);
            f.setMaxLen(max);
            return this;
        }

        FieldBuilder range(String min, String max) {
            f.setMinValue(min == null ? null : new java.math.BigDecimal(min));
            f.setMaxValue(max == null ? null : new java.math.BigDecimal(max));
            return this;
        }

        FieldBuilder dateRule(KycField.DateRule rule) {
            f.setDateRule(rule);
            return this;
        }

        FieldBuilder derivedFrom(String derived) {
            f.setDerivedFrom(derived);
            return this;
        }

        FieldBuilder tag(String canonicalTag) {
            f.setCanonicalTag(canonicalTag);
            return this;
        }

        FieldBuilder sens(KycField.Sensitivity sens, KycField.Mask mask) {
            f.setSensitivity(sens);
            f.setMask(mask);
            return this;
        }

        FieldBuilder listParent(String listParentField) {
            f.setListParentField(listParentField);
            return this;
        }

        FieldBuilder save() {
            fields.save(f);
            return this;
        }
    }

    /** FieldBuilder auto-saves the row when the next statement starts (id-free builder). */
    private FieldBuilder save(FieldBuilder b) {
        return b.save();
    }

    private void rule(KycSchemaVersion sv, String code, KycRule.Phase phase, String validatorKey,
                      String params, String message) {
        KycRule r = new KycRule();
        r.setSchemaVersionId(sv.getId());
        r.setCode(code);
        r.setPhase(phase);
        r.setValidatorKey(validatorKey);
        r.setParams(params);
        r.setMessage(message);
        rules.save(r);
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
