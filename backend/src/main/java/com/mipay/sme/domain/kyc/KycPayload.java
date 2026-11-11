package com.mipay.sme.domain.kyc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The KYC application document exactly as clients send it (businessProfile,
 * operationalDetails, addresses[], ubos[], bankAccounts[]). Stored as JSON on
 * {@link KycApplication}; unknown keys are dropped by re-serialising this record.
 */
public record KycPayload(BusinessProfile businessProfile,
                         OperationalDetails operationalDetails,
                         List<Address> addresses,
                         List<Ubo> ubos,
                         List<BankAccount> bankAccounts) {

    public enum EntityType { SOLE_PROPRIETORSHIP, PARTNERSHIP, PRIVATE_LIMITED, PUBLIC_LIMITED }
    public enum AddressType { REGISTERED, OPERATING, POSTAL }
    public enum IdentificationType { PASSPORT, NATIONAL_ID, DRIVING_LICENSE }
    public enum AccountType { CHECKING, SAVINGS }

    public record BusinessProfile(String legalBusinessName, String tradingName, EntityType entityType,
                                  String registrationNumber, LocalDate incorporationDate,
                                  String taxIdentificationNumber) { }

    public record OperationalDetails(String mcc, String industryVertical, String websiteUrl,
                                     String mobileAppLink, String storefrontDetails,
                                     BigDecimal expectedMonthlyVolume, BigDecimal averageTicketSize) { }

    public record Address(AddressType addressType, String streetLine, String city, String state,
                          String postalCode, String country) { }

    public record Ubo(String fullLegalName, LocalDate dateOfBirth, String nationality,
                      String residentialAddress, IdentificationType identificationType,
                      String identificationNumber, LocalDate identificationExpiryDate,
                      BigDecimal ownershipPercentage, Boolean politicallyExposedPerson) { }

    public record BankAccount(String beneficiaryName, String bankName, String branchName,
                              String institutionCode, String accountNumber, String iban,
                              AccountType accountType) { }
}
