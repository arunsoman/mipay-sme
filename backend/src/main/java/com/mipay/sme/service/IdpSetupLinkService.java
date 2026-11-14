package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.auth.AuthPluginContracts.Enrolment;
import com.mipay.sme.security.auth.AuthPluginContracts.ResetScope;
import com.mipay.sme.security.auth.AuthProviderRegistry;
import com.mipay.sme.security.auth.LinkDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Issues a copyable IdP link without storing its secret in the core. */
@Service
@RequiredArgsConstructor
public class IdpSetupLinkService {
    private final UserRepository users;
    private final IdentityLinkRepository links;
    private final AuthProviderRegistry providers;
    private final OrgTreeService tree;
    private final List<LinkDelivery> deliveries;

    public record Result(String setupLink, Instant expiresAt, boolean delivered, String deliveryMessage) { }

    public Result issue(String userId, Current.Principal caller) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getStatus() != Enums.UserStatus.PENDING)
            throw new ConflictException("USER_ACTIVE", "Set-up links are only available for pending users");
        tree.visible(caller, user.getOrgId());
        IdentityLink identity = links.findByUserId(userId).stream()
                .filter(l -> providers.selected().key().equals(l.getProvider())
                        && l.getSubject() != null && !l.getSubject().isBlank())
                .findFirst().orElseThrow(() -> new ConflictException("NO_CREDENTIAL",
                        "IdP enrolment is still pending; retry after it completes"));
        Enrolment issued = providers.selected().resetCredential(identity.getSubject(), ResetScope.LINK);
        if (issued.oneTimeLink() == null || issued.oneTimeLink().isBlank())
            throw new IllegalStateException("IdP returned no set-up link");
        boolean delivered = !deliveries.isEmpty();
        String message = delivered ? "Sent through the configured comms channel" : "No delivery channel is configured";
        for (LinkDelivery sink : deliveries) {
            try {
                sink.deliver(new LinkDelivery.Request(userId, identity.getSubject(), "ENROL",
                        issued.oneTimeLink(), issued.expiresAt()));
            } catch (RuntimeException e) {
                delivered = false;
                message = "Delivery failed; copy and share the link securely";
            }
        }
        return new Result(issued.oneTimeLink(), issued.expiresAt(), delivered, message);
    }
}
