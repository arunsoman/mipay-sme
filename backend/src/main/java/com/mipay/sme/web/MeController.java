package com.mipay.sme.web;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.security.rbac.PublicEndpoint;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final UserRepository userRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final com.mipay.sme.config.AppProperties props;
    private final com.mipay.sme.repository.OrganizationRepository orgRepo;

    @PublicEndpoint(reason = "current user profile - available to any signed-in user")
    @GetMapping("/api/me")
    public Dtos.MeView me() {
        Current.Principal p = Current.get();
        User user = userRepo.findById(p.userId()).orElseThrow(() -> new NotFoundException("User not found"));

        String merchantId = null;
        String merchantName = null;
        Optional<Merchant> m = merchantRepo.findByUserId(user.getId());
        if (m.isPresent()) {
            merchantId = m.get().getId();
            merchantName = m.get().getBusinessName();
        }
        String supplierId = supplierRepo.findByUserId(user.getId()).map(Supplier::getId).orElse(null);
        String lenderId = lenderRepo.findByUserId(user.getId()).map(Lender::getId).orElse(null);

        boolean simulated = props.isSimulatedEnvironment();
        // org binding for the admin portal shell (null for unbound legacy users)
        String orgType = null;
        if (user.getOrgId() != null) {
            orgType = orgRepo.findById(user.getOrgId()).map(o -> o.getOrgType().name()).orElse(null);
        }
        return new Dtos.MeView(user.getId(), displayName(user, merchantName), user.getRole().name(),
                user.getPhone(), merchantId, supplierId, lenderId, props.getEnvironment(), simulated,
                user.getUsername(), user.getOrgId(), orgType,
                user.getOrgRole() == null ? null : user.getOrgRole().name());
    }

    private String displayName(User user, String merchantName) {
        if (merchantName != null && !merchantName.isBlank()) return merchantName;
        return user.getName() == null ? user.getPhone() : user.getName();
    }
}
