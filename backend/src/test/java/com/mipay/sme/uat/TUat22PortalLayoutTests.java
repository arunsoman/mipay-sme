package com.mipay.sme.uat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.ui.PortalLayoutService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Portal layouts (portal-builder.md steps 5 + 7): draft → submitted → published by another
 * person; portals read only the published one; the builder API is off without its key.
 */
@DisplayName("Portal builder · layouts with maker/checker")
class TUat22PortalLayoutTests extends UatSupport {

    @Autowired PortalLayoutService layouts;

    private JsonNode layout(String portal, String menuLabel) throws Exception {
        return om.readTree("""
            {"format":1,"portal":"%s","version":1,"locales":["en"],"menus":[
              {"id":"kyc","icon":"","label":{"key":"portal.x.kyc","messages":{"en":"%s"}},"screens":[
                {"uid":"s123","id":"queue","label":{"key":"portal.x.kyc.queue","messages":{"en":"Review queue"}},
                 "items":[{"instanceId":"i1","widgetId":"kyc.tasks","major":2,"config":{}}]},
                {"uid":"%s/onboarding/documents","legacy":true,"id":"documents","label":{"key":"portal.x.kyc.documents","messages":{"en":"KYC"}},"items":[]}
              ]}]}""".formatted(portal, menuLabel, portal));
    }

    @Test
    @DisplayName("draft, submit, approve by someone else; a new version retires the old; unpublish goes back to coded menus")
    void lifecycle() throws Exception {
        assertThat(layouts.published("DISTRIBUTOR")).isNull();
        layouts.saveDraft("DISTRIBUTOR", "maker", layout("DISTRIBUTOR", "KYC"));
        layouts.submit("DISTRIBUTOR", "maker", "first layout");

        assertThatThrownBy(() -> layouts.decide("DISTRIBUTOR", "maker", true, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("FOUR_EYES"));
        var v1 = layouts.decide("DISTRIBUTOR", "checker", true, "looks good");
        assertThat(v1.status()).isEqualTo("PUBLISHED");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(layouts.published("DISTRIBUTOR").path("menus").get(0).path("label").path("messages").path("en").asText()).isEqualTo("KYC");

        // version 2 retires version 1; a rejected submission changes nothing live
        layouts.saveDraft("DISTRIBUTOR", "maker", layout("DISTRIBUTOR", "Know your customer"));
        layouts.submit("DISTRIBUTOR", "maker", null);
        assertThat(layouts.decide("DISTRIBUTOR", "checker", false, "wrong label").status()).isEqualTo("REJECTED");
        assertThat(layouts.published("DISTRIBUTOR").path("version").asInt()).isEqualTo(1);
        layouts.saveDraft("DISTRIBUTOR", "maker", layout("DISTRIBUTOR", "Know your customer"));
        layouts.submit("DISTRIBUTOR", "maker", null);
        assertThat(layouts.decide("DISTRIBUTOR", "checker", true, null).version()).isEqualTo(2);
        JsonNode state = om.valueToTree(layouts.state("DISTRIBUTOR"));
        assertThat(state.path("published").path("summary").path("version").asInt()).isEqualTo(2);
        assertThat(state.path("history").toString()).contains("RETIRED").contains("REJECTED");

        layouts.unpublish("DISTRIBUTOR", "checker");
        assertThat(layouts.published("DISTRIBUTOR")).isNull();
    }

    @Test
    @DisplayName("a layout that would break a sidebar is refused")
    void invalidLayouts() throws Exception {
        assertThatThrownBy(() -> layouts.saveDraft("SUPPLIER", "maker", layout("MERCHANT", "KYC")))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getMessage()).contains("not SUPPLIER"));
        assertThatThrownBy(() -> layouts.saveDraft("SUPPLIER", "maker", layout("SUPPLIER", "")))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getMessage()).contains("English label"));
        assertThatThrownBy(() -> layouts.saveDraft("SUPPLIER", "maker", layout("SUPPLIER", "<b>KYC</b>")))
                .hasMessageContaining("no markup");
        assertThatThrownBy(() -> layouts.saveDraft("NOWHERE", "maker", layout("NOWHERE", "KYC")))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo("PORTAL_UNKNOWN"));
    }

    @Test
    @DisplayName("HTTP: the builder API is off without its key; portals read the published layout (204 when none)")
    void http() throws Exception {
        MvcResult off = mockMvc.perform(MockMvcRequestBuilders.get("/internal/ui/builder/whoami")
                .header("X-Builder-Key", "anything").header("X-Builder-User", "maker")).andReturn();
        assertThat(off.getResponse().getStatus()).isEqualTo(404);

        String token = adminToken();
        MvcResult none = mockMvc.perform(MockMvcRequestBuilders.get("/api/ui/layouts/MANUFACTURER")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(204);
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/api/ui/layouts/MANUFACTURER")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);

        layouts.saveDraft("MANUFACTURER", "maker", layout("MANUFACTURER", "KYC"));
        layouts.submit("MANUFACTURER", "maker", null);
        layouts.decide("MANUFACTURER", "checker", true, null);
        MvcResult live = mockMvc.perform(MockMvcRequestBuilders.get("/api/ui/layouts/MANUFACTURER")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(live.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(live.getResponse().getContentAsString()).path("version").asInt()).isEqualTo(1);
    }
}
