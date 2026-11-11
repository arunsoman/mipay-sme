package com.mipay.sme.uat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.domain.kyc.KycApplication;
import com.mipay.sme.domain.kyc.KycTask;
import com.mipay.sme.repository.KycTaskRepository;
import com.mipay.sme.repository.UserPlatformFunctionRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.KycApplicationService;
import com.mipay.sme.service.OrgOnboardingService;
import com.mipay.sme.service.OrgProvisioningService;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KYC applications on the master (TC-KYCAPP, §14): the schema-driven document
 * replaces the fixed payload; drafts validate shape, submissions completeness
 * + cross-field rules; the ops-review stage runs the single go-live gate and
 * the maker never acts on their own submission.
 */
@DisplayName("KYC applications: master-driven document + workflow gate — TC-KYCAPP")
@TestMethodOrder(OrderAnnotation.class)
class TUat19KycApplicationTests extends UatSupport {

    private static final String PW = "S3cure-Password!42";

    @Autowired KycApplicationService kycApps;
    @Autowired KycTaskRepository taskRepo;
    @Autowired UserPlatformFunctionRepository platformFunctions;

    private String distOrg;
    private String supOrg;
    private String merOrg;
    private User distAdmin;
    private User supAdmin;

    /** A shape-correct but incomplete document: saves as a draft, cannot be submitted. */
    private static final String SPARSE_JSON = """
        {"businessProfile":{"legalBusinessName":"Sparse Holding %s (Pty) Ltd",
          "entityType":"PRIVATE_COMPANY","registrationNumber":"CC/2020/%s",
          "taxIdentificationNumber":"20%s","countryOfRegistration":"NA"}}
        """;

    private Map<String, Object> validDoc() throws Exception {
        return om.readValue("""
            {
              "businessProfile": {"legalBusinessName": "Namib Distribution %d (Pty) Ltd", "tradingName": "Namib Dist",
                "entityType": "PRIVATE_COMPANY", "registrationNumber": "CC/2019/%06d",
                "incorporationDate": "2019-03-14", "taxIdentificationNumber": "10%06d",
                "countryOfRegistration": "NA"},
              "addresses": [{"addressType": "REGISTERED", "streetLine": "12 Sam Nujoma Dr", "town": "Windhoek",
                "region": "KHOMAS", "postalCode": "10005", "country": "NA"}],
              "ubos": [{"fullLegalName": "Petrus Haufiku", "dateOfBirth": "1985-04-12", "nationality": "NA",
                "identificationType": "NATIONAL_ID",
                "identificationNumber": "85041200123",
                "ownershipType": "DIRECT", "ownershipPercentage": 60, "politicallyExposedPerson": false,
                "roles": ["SHAREHOLDER", "DIRECTOR"]}],
              "bankAccounts": [{"beneficiaryName": "Namib Distribution %d (Pty) Ltd", "bankCode": "BANK_WINDHOEK",
                "branch": "WINDHOEK", "accountNumber": "8001234567",
                "accountType": "CURRENT", "currency": "NAD"}]
            }""".formatted(seq(), seq(), seq(), seq(), seq()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    @BeforeAll
    void buildChain() throws Exception {
        orgProvisioning.ensurePlatformRoot();

        // the platform onboards a DISTRIBUTOR (the KYC draft is created by onboarding itself, §12)
        String dPhone = phone();
        Dtos.OrgDecisionResult d = orgOnboard.submitOrg(platformAdminPrincipal(), Enums.OrgType.DISTRIBUTOR,
                new OrgOnboardingService.OrgSpec("KYC Dist " + seq(), "REG-KYC-D-" + seq(), "DISTRIBUTION",
                        "Dist Owner " + seq(), dPhone, null, "kyc-dist-" + seq() + "@uat.na",
                        null, null, null, null, null, null, null, null));
        distOrg = d.orgId();
        // distributor's KYC: saved + submitted by the platform admin, approved by ops review (gate)
        kycGoLive(platformAdminPrincipal(), distOrg, opsPrincipal());
        assertThat(orgs.findById(distOrg).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.ACTIVE);
        distAdmin = users.findById(users.findByPhone(dPhone).orElseThrow().getId()).orElseThrow();
        activation.activate(rawTokenFor(distAdmin.getId()), PW);
        assertThat(users.findById(distAdmin.getId()).orElseThrow().getStatus()).isEqualTo(Enums.UserStatus.ACTIVE);

        // the distributor onboards a SUPPLIER (draft left empty)
        String sPhone = phone();
        Dtos.OrgDecisionResult s = orgOnboard.submitOrg(principalOf(distAdmin), Enums.OrgType.SUPPLIER,
                new OrgOnboardingService.OrgSpec("KYC Supplier " + seq(), null, null, "Sup Owner " + seq(), sPhone,
                        null, null, "Bank Windhoek", "2000" + String.format("%05d", seq() % 100_000),
                        "Sup Owner", null, null, null, null, null));
        supOrg = s.orgId();
        assertThat(users.findByPhone(sPhone).orElseThrow().getStatus()).isEqualTo(Enums.UserStatus.PENDING);
        supAdmin = users.findByPhone(sPhone).orElseThrow();
    }

    @Test
    @Order(1)
    @DisplayName("TC-KYCAPP-01 · the two-call onboarding keys a master-driven DRAFT with tree details")
    void tc01_onboardingCreatesDraft() {
        KycApplication app = open(distOrg);
        assertThat(app.getStatus()).isEqualTo(KycApplication.Status.APPROVED);
        assertThat(app.getOrgType()).isEqualTo(Enums.OrgType.DISTRIBUTOR);
        assertThat(app.getCountry()).isEqualTo("NA");
        assertThat(app.getLicenceType()).isEqualTo("PSP");
        assertThat(app.getSchemaVersionId()).isNotBlank();
        assertThat(app.getProfileId()).isNotBlank();
        assertThat(app.getParentOrgId()).isEqualTo(OrgProvisioningService.ORG_ROOT);
        assertThat(app.getTreePath()).isEqualTo(OrgProvisioningService.ORG_ROOT + "/" + distOrg);
        assertThat(app.getDepth()).isEqualTo(1);
        assertThat(app.isSuspended()).isFalse();
        assertThat(app.getWorkflowKey()).isEqualTo("configurable");

        // the supplier's document is still the empty draft keyed at onboarding (D8)
        KycApplication sup = kycRepo.findByOrgIdAndStatusIn(supOrg, KycApplication.OPEN_STATUSES).stream()
                .findFirst().orElseThrow();
        assertThat(sup.getStatus()).isEqualTo(KycApplication.Status.DRAFT);
        assertThat(sup.getTreePath()).isEqualTo(String.join("/", OrgProvisioningService.ORG_ROOT, distOrg, supOrg));

        // the PENDING supplier stays pending until its own KYC gate (D8)
        assertThat(orgs.findById(supOrg).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.PENDING);
    }

    @Test
    @Order(2)
    @DisplayName("TC-KYCAPP-02 · HTTP: a sparse, shape-correct document saves as draft; submit lists everything missing")
    void tc02_httpRoundTripAndCompleteness() throws Exception {
        String token = loginWithPassword(distAdmin.getPhone(), PW).token();
        String n = String.valueOf(seq());
        String sparse = SPARSE_JSON.formatted("UAT Sparse " + n, String.format("%06d", seq() % 1_000_000),
                String.format("%06d", seq() % 1_000_000));

        MvcResult put = mockMvc.perform(MockMvcRequestBuilders.put("/api/orgs/" + supOrg + "/kyc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(sparse))
                .andReturn();
        assertThat(put.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = om.readTree(put.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("DRAFT");
        assertThat(view.get("isSuspended").asBoolean()).isFalse();
        assertThat(view.get("depth").asInt()).isEqualTo(2);
        assertThat(view.at("/application/businessProfile/legalBusinessName").asText())
                .contains("UAT Sparse " + n);

        // stored as (encrypted) JSON in the row
        KycApplication stored = open(supOrg);
        JsonNode rawDoc = om.readTree(stored.getApplicationJson());
        assertThat(rawDoc.at("/businessProfile/entityType").asText()).isEqualTo("PRIVATE_COMPANY");

        // submit lists every reason the sparse document is not acceptable
        MvcResult sub = bearerPost("/api/orgs/" + supOrg + "/kyc/submit", token, null).andReturn();
        assertThat(sub.getResponse().getStatus()).isEqualTo(400);
        JsonNode problem = om.readTree(sub.getResponse().getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("KYC_INCOMPLETE");
        assertThat(problem.get("detail").asText())
                .contains("at least 1 addresses")
                .contains("at least 1 ubos")
                .contains("at least 1 bankAccounts");

        // now the completeness rules of the master: 115% ownership and two registered addresses
        Map<String, Object> overOwned = kycDoc(supOrg);
        Map<String, Object> second = new java.util.LinkedHashMap<>(uboOf(overOwned));
        second.put("ownershipPercentage", 55);
        second.put("fullLegalName", "Second Owner");
        List<Object> ubos = new java.util.ArrayList<>(uboList(overOwned));
        ubos.add(second);
        overOwned.put("ubos", ubos);
        kycApps.saveDraft(principalOf(distAdmin), supOrg, overOwned, null);
        MvcResult over = bearerPost("/api/orgs/" + supOrg + "/kyc/submit", token, null).andReturn();
        assertThat(over.getResponse().getStatus()).isEqualTo(400);
        assertThat(om.readTree(over.getResponse().getContentAsString()).get("detail").asText())
                .contains("combined ownership");

        Map<String, Object> twoRegistered = kycDoc(supOrg);
        List<Object> addresses = new java.util.ArrayList<>(listOf(twoRegistered, "addresses"));
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        copy.put("addressType", "REGISTERED");
        copy.put("streetLine", "x");
        copy.put("town", "y");
        copy.put("country", "NA");
        addresses.add(copy);
        twoRegistered.put("addresses", addresses);
        kycApps.saveDraft(principalOf(distAdmin), supOrg, twoRegistered, null);
        MvcResult twice = bearerPost("/api/orgs/" + supOrg + "/kyc/submit", token, null).andReturn();
        assertThat(twice.getResponse().getStatus()).isEqualTo(400);
        assertThat(om.readTree(twice.getResponse().getContentAsString()).get("detail").asText())
                .contains("at most 1 matching addressType=[REGISTERED] allowed");

        // the full document passes; sendToChecker=false lands in the ops-review stage
        mockMvc.perform(MockMvcRequestBuilders.put("/api/orgs/" + supOrg + "/kyc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(kycDoc(supOrg))));
        MvcResult ok = bearerPost("/api/orgs/" + supOrg + "/kyc/submit", token, null).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(ok.getResponse().getContentAsString()).get("status").asText()).isEqualTo("IN_REVIEW");

        // an ops-review task is open for platform review; the supplier's org is still PENDING
        assertThat(openTask(supOrg)).isNotNull();
        assertThat(orgs.findById(supOrg).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.PENDING);
    }

    @Test
    @Order(3)
    @DisplayName("TC-KYCAPP-03 · drafts refuse malformed documents (unknown keys, bad choices, bad formats)")
    void tc03_structuralRules() throws Exception {
        // unknown keys are rejected
        Map<String, Object> unknownKey = kycDoc(supOrg);
        unknownKey.put("legacyExtras", Map.of("whatever", 1));
        assertThatThrownBy(() -> kycApps.saveDraft(principalOf(distAdmin), supOrg, unknownKey, null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("KYC_INVALID");
                    assertThat(e.getMessage()).contains("unknown keys");
                });

        // a bank branch that does not belong to the selected bank is refused (§5.2 list parent)
        Map<String, Object> wrongBranch = kycDoc(supOrg);
        Map<String, Object> bank = listOf(wrongBranch, "bankAccounts").stream()
                .map(o -> (Map<String, Object>) o).findFirst().orElseThrow();
        bank.put("bankCode", "FNB_NAMIBIA");
        assertThatThrownBy(() -> kycApps.saveDraft(principalOf(distAdmin), supOrg, wrongBranch, null))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getMessage()).contains("not a valid choice"));

        // a malformed registration number fails the master's BIPA pattern even on drafts
        Map<String, Object> badPattern = kycDoc(supOrg);
        Map<String, Object> bp = java.util.Objects.requireNonNull(
                (Map<String, Object>) badPattern.get("businessProfile"));
        bp.put("registrationNumber", "1234-BAD");
        assertThatThrownBy(() -> kycApps.saveDraft(principalOf(distAdmin), supOrg, badPattern, null))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getMessage()).contains("invalid format"));
    }

    @Test
    @Order(4)
    @DisplayName("TC-KYCAPP-04 · submitted documents are locked; reject terminates; approve runs the gate")
    void tc04_lockGateAndIndependence() {
        // supplier KYC was submitted by the distributor admin in TC-02: editing is refused (lock)
        assertThatThrownBy(() -> kycApps.saveDraft(principalOf(distAdmin), supOrg, kycDoc(supOrg), null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("KYC_LOCKED"));

        // an org admin cannot act on a platform stage; a platform admin without the function neither
        String taskId = openTask(supOrg).getId();
        assertThatThrownBy(() -> kycApps.decideTask(principalOf(distAdmin), taskId, "APPROVE", null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> kycApps.decideTask(platformAdminPrincipal(), taskId, "APPROVE", null))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NO_FUNCTION"));

        // a second distributor whose review REJECTS: the org is terminated and pending users archived
        String rPhone = phone();
        Dtos.OrgDecisionResult d = orgOnboard.submitOrg(platformAdminPrincipal(), Enums.OrgType.DISTRIBUTOR,
                new OrgOnboardingService.OrgSpec("KYC Reject Dist " + seq(), "REG-KYC-R-" + seq(), "DISTRIBUTION",
                        "Reject Owner " + seq(), rPhone, null, null, null, null, null, null, null, null, null, null));
        kycGoLive(platformAdminPrincipal(), d.orgId(), opsPrincipal(), "REJECT", "bank letter missing");
        assertThat(open(d.orgId()).getStatus()).isEqualTo(KycApplication.Status.REJECTED);
        assertThat(orgs.findById(d.orgId()).orElseThrow().getLifecycle()).isEqualTo(Enums.OrgStatus.REJECTED);
        User rejectedAdmin = users.findByPhone(rPhone).orElseThrow();
        assertThat(users.findById(rejectedAdmin.getId()).orElseThrow().getStatus()).isEqualTo(Enums.UserStatus.SUSPENDED);
        assertThat(users.findById(rejectedAdmin.getId()).orElseThrow().getUsername()).startsWith("arch-");

        // a fresh DRAFT can be opened after rejection (the business can start over)
        Dtos.KycApplicationView reopened = kycApps.saveDraft(platformAdminPrincipal(), d.orgId(), kycDoc(d.orgId()), null);
        assertThat(reopened.status()).isEqualTo("DRAFT");

        // ops approves the supplier: the gate activates the org + provisioning (§6.5)
        kycApps.decideTask(opsPrincipal(), taskId, "APPROVE", null);
        KycApplication approved = open(supOrg);
        assertThat(approved.getStatus()).isEqualTo(KycApplication.Status.APPROVED);
        assertThat(approved.getDecidedBy()).isEqualTo(opsPrincipal().userId());
        assertThat(approved.getRiskRating()).isNotBlank();
        assertThat(approved.getNextReviewDate()).isNotNull();
        Organization supplierOrg = orgs.findById(supOrg).orElseThrow();
        assertThat(supplierOrg.getLifecycle()).isEqualTo(Enums.OrgStatus.ACTIVE);
        assertThat(supplierOrg.getKycStatus()).isEqualTo(Enums.KycStatus.VERIFIED);
        assertThat(supplierOrg.getKycRiskRating()).isNotNull();
        assertThat(supplierOrg.getKycNextReviewDate()).isNotNull();

        // the activation link was issued by the gate ([D8]); the admin activates and logs in
        assertThat(simOutbox.findAll().stream()
                .filter(o -> supAdmin.getId().equals(o.getUserId())).findFirst().orElse(null)).isNotNull();
        activation.activate(rawTokenFor(supAdmin.getId()), PW);
        supAdmin = users.findById(supAdmin.getId()).orElseThrow();
        assertThat(supAdmin.getStatus()).isEqualTo(Enums.UserStatus.ACTIVE);

        // now the supplier (ACTIVE) onboards a MERCHANT — the grand-child — with an empty draft
        String mPhone = phone();
        Dtos.OrgDecisionResult m = orgOnboard.submitOrg(principalOf(supAdmin), Enums.OrgType.MERCHANT,
                new OrgOnboardingService.OrgSpec("KYC Merchant " + seq(), null, "RETAIL", "Mer Owner " + seq(), mPhone,
                        null, null, null, null, null, null, null, null, null, null));
        merOrg = m.orgId();
        assertThat(kycRepo.findByOrgIdAndStatusIn(merOrg, KycApplication.OPEN_STATUSES)
                .stream().findFirst().orElseThrow().getStatus()).isEqualTo(KycApplication.Status.DRAFT);
        kycApps.saveDraft(principalOf(supAdmin), merOrg, kycDoc(merOrg), null);
    }

    @Test
    @Order(5)
    @DisplayName("TC-KYCAPP-05 · isSuspended follows holds across the subtree; independent holds stay in force")
    void tc05_isSuspendedSync() {
        Dtos.SuspensionRow distHold = orgSusp.suspend(distOrg, "audit", platformAdminPrincipal());
        assertThat(open(supOrg).isSuspended()).isTrue();
        assertThat(open(merOrg).isSuspended()).isTrue();
        assertThat(kycApps.list(platformAdminPrincipal(), null, true))
                .extracting(Dtos.KycSummaryRow::orgId).contains(distOrg, supOrg, merOrg);

        // a second, independent hold on the supplier (by the platform)
        orgSusp.suspend(supOrg, "bank review", platformAdminPrincipal());
        orgSusp.lift(distHold.id(), platformAdminPrincipal());
        assertThat(open(distOrg).isSuspended()).isFalse();
        assertThat(open(supOrg).isSuspended()).isTrue();
        assertThat(open(merOrg).isSuspended()).isTrue();

        String supHold = suspensionRepo.findByOrgIdAndLiftedAtIsNull(supOrg).get(0).getId();
        orgSusp.lift(supHold, platformAdminPrincipal());
        assertThat(open(supOrg).isSuspended()).isFalse();
        assertThat(open(merOrg).isSuspended()).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("TC-KYCAPP-06 · visibility: parent sees in full, grand-parent masked, outsiders 404")
    void tc06_visibilityAndMasking() {
        Dtos.KycApplicationView byParent = kycApps.view(principalOf(supAdmin), merOrg);
        assertThat(byParent.masked()).isFalse();
        assertThat(byParent.application().at("/ubos/0/identificationNumber").asText()).isEqualTo("85041200123");

        Dtos.KycApplicationView byGrandParent = kycApps.view(principalOf(distAdmin), merOrg);
        assertThat(byGrandParent.masked()).isTrue();
        // the master's mask rules: SECRET → LAST4, PII dates → HIDE
        assertThat(byGrandParent.application().at("/ubos/0/identificationNumber").asText())
                .isEqualTo("****0123");
        assertThat(byGrandParent.application().at("/ubos/0/dateOfBirth").asText()).isEqualTo("••••••");
        assertThat(byGrandParent.canEdit()).isFalse();

        // the supplier cannot see its parent's KYC; an unrelated org is 404
        assertThatThrownBy(() -> kycApps.view(principalOf(supAdmin), distOrg))
                .isInstanceOf(com.mipay.sme.common.NotFoundException.class);
        // grand-parent cannot edit
        assertThatThrownBy(() -> kycApps.saveDraft(principalOf(distAdmin), merOrg, kycDoc(merOrg), null))
                .isInstanceOf(ForbiddenException.class);

        // an org with no document at all (platform root) reports NOT_STARTED
        Dtos.KycApplicationView none = kycApps.view(platformAdminPrincipal(), OrgProvisioningService.ORG_ROOT);
        assertThat(none.status()).isEqualTo("NOT_STARTED");
    }

    @Test
    @Order(7)
    @DisplayName("TC-KYCAPP-07 · maker-checker entry check: a second ADMIN in the maker's org must confirm")
    void tc07_entryChecker() throws Exception {
        // the merchant submits its KYC with the optional entry-check flag from its own (supplier) org
        Dtos.KycApplicationView merDoc = kycApps.saveDraft(principalOf(supAdmin), merOrg, kycDoc(merOrg), null);
        assertThat(merDoc.status()).isEqualTo("DRAFT");
        // the supplier org has exactly one active ADMIN → the maker-checker cannot proceed
        assertThatThrownBy(() -> kycApps.submit(principalOf(supAdmin), merOrg, true))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ENTRY_CHECKER_UNAVAILABLE"));
    }

    @Test
    @Order(8)
    @DisplayName("TC-KYCAPP-08 · withdraw keeps a fresh application out of review")
    void tc08_withdraw() {
        // the merchant's own draft can be withdrawn by its owner
        Dtos.KycApplicationView w = kycApps.withdraw(principalOf(supAdmin), merOrg);
        assertThat(w.status()).isEqualTo("WITHDRAWN");
    }

    // ---------------------------------------------------------------- helpers

    private KycApplication open(String orgId) {
        return kycRepo.findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(orgId, KycApplication.OPEN_STATUSES)
                .orElseGet(() -> kycRepo.findFirstByOrgIdOrderByCreatedAtDesc(orgId).orElseThrow());
    }

    private KycTask openTask(String orgId) {
        KycApplication app = open(orgId);
        return taskRepo.findByApplicationIdAndSubmissionNo(app.getId(), app.getSubmissionNo()).stream()
                .filter(t -> t.getStatus() == KycTask.Status.OPEN)
                .findFirst().orElse(null);
    }

    private Map<String, Object> uboOf(Map<String, Object> doc) {
        @SuppressWarnings("unchecked")
        Map<String, Object> src = new java.util.LinkedHashMap<>(
                (Map<String, Object>) ((List<?>) doc.get("ubos")).get(0));
        return src;
    }

    private List<?> uboList(Map<String, Object> doc) {
        return (List<?>) doc.get("ubos");
    }

    private List<?> listOf(Map<String, Object> doc, String key) {
        return (List<?>) doc.get(key);
    }

    private String rawTokenFor(String userId) {
        return simOutbox.findAll().stream()
                .filter(o -> userId.equals(o.getUserId()))
                .findFirst().orElseThrow().getRawToken();
    }
}
