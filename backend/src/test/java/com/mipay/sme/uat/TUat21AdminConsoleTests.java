package com.mipay.sme.uat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.OrgAccessSettingRepository;
import com.mipay.sme.service.OrgOnboardingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin console's server side (docs/plans/admin-console.md), over the real HTTP stack:
 * every list searches, filters, sorts and pages on the server (ListQuery), and an org's
 * features start from a preset at onboarding. Runs without RBAC (H2): applying features in
 * RBAC is covered by OrgFeatureServiceTest; here the refusal shows as a warning.
 */
@DisplayName("Admin console · server-side lists and org features")
class TUat21AdminConsoleTests extends UatSupport {

    @Autowired OrgAccessSettingRepository accessSettings;

    String distId;
    String distName;

    @BeforeAll
    void onboard() {
        orgProvisioning.ensurePlatformRoot();
        distName = "Console Dist " + seq();
        distId = newDistributor(distName);
    }

    private String newDistributor(String name) {
        return orgOnboard.submitOrg(platformAdminPrincipal(), Enums.OrgType.DISTRIBUTOR,
                new OrgOnboardingService.OrgSpec(name, "REG-CONS-" + seq(), "DISTRIBUTION",
                        "Owner " + seq(), phone(), null, "cons-" + seq() + "@uat.na", null, null, null, null, null, null,
                        Set.of(Enums.OrgType.MERCHANT), Set.of(Enums.OrgType.MERCHANT), null, null)).orgId();
    }

    private JsonNode get(String url, String token, int status) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.get(java.net.URI.create(url)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(url + " → " + r.getResponse().getContentAsString()).isEqualTo(status);
        return om.readTree(r.getResponse().getContentAsString());
    }

    private static String enc(String s) {
        // as a browser sends it (get() takes a URI, so MockMvc does not encode it again)
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    @DisplayName("onboarding starts the new org on the full preset, saved for go-live")
    void onboardingSavesThePreset() {
        var s = accessSettings.findById(distId).orElseThrow();
        assertThat(s.getPresetCode()).isEqualTo("full");
        assertThat(s.getFeatures()).contains("team", "onboarding");
        assertThat(s.getAppliedAt()).isNull();
    }

    @Test
    @DisplayName("organizations: search by name, filter by type, sort, page; rows carry KYC status, users and preset")
    void organizationsList() throws Exception {
        String token = adminToken();
        JsonNode p = get("/api/admin/organizations/search?q=" + enc(distName.toLowerCase()) + "&type=DISTRIBUTOR", token, 200);
        assertThat(p.get("total").asLong()).isEqualTo(1);
        JsonNode row = p.get("items").get(0);
        assertThat(row.get("id").asText()).isEqualTo(distId);
        assertThat(row.get("users").asLong()).isEqualTo(1);
        assertThat(row.get("preset").asText()).isEqualTo("full");
        assertThat(row.get("status").asText()).isEqualTo("PENDING");

        JsonNode sorted = get("/api/admin/organizations/search?sort=name,desc&size=5", token, 200);
        assertThat(sorted.get("size").asInt()).isEqualTo(5);
        assertThat(sorted.get("items").size()).isLessThanOrEqualTo(5);
        String first = sorted.get("items").get(0).get("name").asText().toLowerCase();
        String second = sorted.get("items").get(1).get("name").asText().toLowerCase();
        assertThat(first.compareTo(second)).isGreaterThanOrEqualTo(0);

        // orgs onboarded before features existed warn "Access not set"; the filter finds them
        JsonNode warned = get("/api/admin/organizations/search?warnings=" + enc("Access not set") + "&size=100", token, 200);
        for (JsonNode r : warned.get("items")) assertThat(r.get("warnings").toString()).contains("Access not set");
    }

    @Test
    @DisplayName("fail-closed: an undeclared sort or filter is a 400")
    void undeclaredIsRefused() throws Exception {
        String token = adminToken();
        JsonNode bad = get("/api/admin/organizations/search?sort=password", token, 400);
        assertThat(bad.toString()).contains("BAD_SORT");
        assertThat(get("/api/admin/organizations/search?secret=1", token, 400).toString()).contains("BAD_FILTER");
        assertThat(get("/api/kyc/orgs/search?sort=applicationJson", token, 400).toString()).contains("BAD_SORT");
    }

    @Test
    @DisplayName("KYC applications: searched in the database by org name, filtered by status, paged")
    void kycApplications() throws Exception {
        String token = adminToken();
        JsonNode p = get("/api/kyc/orgs/search?q=" + enc(distName) + "&status=DRAFT", token, 200);
        assertThat(p.get("total").asLong()).isEqualTo(1);
        assertThat(p.get("items").get(0).get("orgId").asText()).isEqualTo(distId);
        assertThat(get("/api/kyc/orgs/search?q=" + enc(distName) + "&status=APPROVED", token, 200).get("total").asLong()).isZero();
        JsonNode page = get("/api/kyc/orgs/search?size=1&sort=orgName,asc", token, 200);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("KYC review queue: the same contract (search, filter, sort, page)")
    void kycTasks() throws Exception {
        JsonNode p = get("/api/kyc/tasks/search?sort=createdAt,desc&size=10", opsToken(), 200);
        assertThat(p.has("items")).isTrue();
        assertThat(p.has("total")).isTrue();
        assertThat(p.get("size").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("people: an org's users with their roles, searched by the server")
    void people() throws Exception {
        User admin = users.findByOrgId(distId).getFirst();
        JsonNode p = get("/api/orgs/" + distId + "/people?q=" + enc(admin.getName()) + "&orgRole=ADMIN", adminToken(), 200);
        assertThat(p.get("total").asLong()).isEqualTo(1);
        JsonNode row = p.get("items").get(0);
        assertThat(row.get("username").asText()).isEqualTo(admin.getUsername());
        assertThat(row.get("roles").isArray()).isTrue();
        assertThat(get("/api/orgs/" + distId + "/people?orgRole=STAFF", adminToken(), 200).get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("features: the tab shows the menus as switches; switching to KYC only warns 'Can't add users'")
    void features() throws Exception {
        String token = adminToken();
        String distId = newDistributor("Console Features " + seq());   // its own org: the other tests read the shared one
        JsonNode v = get("/api/orgs/" + distId + "/features", token, 200);
        assertThat(v.get("preset").asText()).isEqualTo("full");
        assertThat(v.get("features").toString()).contains("\"id\":\"team\"").contains("\"label\":\"Team and access\"");
        assertThat(v.get("presets").size()).isEqualTo(3);

        MvcResult put = mockMvc.perform(MockMvcRequestBuilders.put("/api/orgs/" + distId + "/features")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("preset", "kyc_only"))))
                .andReturn();
        assertThat(put.getResponse().getStatus()).as(put.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode after = om.readTree(put.getResponse().getContentAsString());
        assertThat(after.get("preset").asText()).isEqualTo("kyc_only");
        List<String> warnings = om.convertValue(after.get("warnings"), List.class);
        // no RBAC on H2: the choice is saved and the failed apply is a warning, not an error
        assertThat(warnings).contains("Can't add users", "Access not applied");

        // an unknown menu is refused
        MvcResult bad = mockMvc.perform(MockMvcRequestBuilders.put("/api/orgs/" + distId + "/features")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("features", List.of("payroll")))))
                .andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("UNKNOWN_FEATURE");
    }
}
