package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.auth.AuthPluginContracts;
import com.mipay.sme.security.auth.AuthProviderRegistry;
import com.mipay.sme.security.auth.LinkDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdpSetupLinkServiceTest {
    @Mock UserRepository users;
    @Mock IdentityLinkRepository links;
    @Mock AuthProviderRegistry registry;
    @Mock OrgTreeService tree;
    @Mock AuthPluginContracts.AuthProvider provider;
    @Mock LinkDelivery delivery;
    private final Current.Principal manager = new Current.Principal("manager", "ORG_ADMIN", "Manager", "",
            null, null, null, "org-1", "MANUFACTURER", "ADMIN");

    private IdpSetupLinkService ready() {
        User user = new User();
        user.setId("user-1");
        user.setOrgId("org-1");
        user.setStatus(Enums.UserStatus.PENDING);
        IdentityLink link = new IdentityLink();
        link.setProvider("trustplane");
        link.setSubject("acc-1");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(links.findByUserId("user-1")).thenReturn(List.of(link));
        when(registry.selected()).thenReturn(provider);
        when(provider.key()).thenReturn("trustplane");
        return new IdpSetupLinkService(users, links, registry, tree, List.of(delivery));
    }

    @Test void issuesAndDeliversTheIdpLink() {
        var service = ready();
        var expiry = Instant.now().plusSeconds(3600);
        when(provider.resetCredential("acc-1", AuthPluginContracts.ResetScope.LINK))
                .thenReturn(new AuthPluginContracts.Enrolment("acc-1", "https://idp.test/account/setup?token=secret", expiry));
        var result = service.issue("user-1", manager);
        assertTrue(result.delivered());
        assertEquals("https://idp.test/account/setup?token=secret", result.setupLink());
        verify(tree).visible(manager, "org-1");
        verify(delivery).deliver(new LinkDelivery.Request("user-1", "acc-1", "ENROL", result.setupLink(), expiry));
    }

    @Test void deliveryFailureStillReturnsCopyableLinkAndHonestStatus() {
        var service = ready();
        when(provider.resetCredential("acc-1", AuthPluginContracts.ResetScope.LINK))
                .thenReturn(new AuthPluginContracts.Enrolment("acc-1", "https://idp.test/setup", null));
        doThrow(new IllegalStateException("comms unavailable")).when(delivery).deliver(any());
        var result = service.issue("user-1", manager);
        assertFalse(result.delivered());
        assertEquals("https://idp.test/setup", result.setupLink());
    }

    @Test void visibilityDenialCannotIssueLink() {
        var service = ready();
        doThrow(new NotFoundException("hidden")).when(tree).visible(manager, "org-1");
        assertThrows(NotFoundException.class, () -> service.issue("user-1", manager));
        verify(provider, never()).resetCredential(anyString(), any());
    }

    @Test void activeUserCannotReceiveSetupLink() {
        var service = ready();
        User active = new User();
        active.setId("user-1");
        active.setStatus(Enums.UserStatus.ACTIVE);
        when(users.findById("user-1")).thenReturn(Optional.of(active));
        assertThrows(ConflictException.class, () -> service.issue("user-1", manager));
        verifyNoInteractions(delivery);
    }
}
