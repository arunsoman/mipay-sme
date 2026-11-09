package com.mipay.sme.web;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.User;
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
        return new Dtos.MeView(user.getId(), displayName(user, merchantName), user.getRole().name(), user.getPhone(),
                merchantId, supplierId, lenderId, props.getEnvironment(), simulated);
    }

    private String displayName(User user, String merchantName) {
        if (merchantName != null && !merchantName.isBlank()) return merchantName;
        return user.getName() == null ? user.getPhone() : user.getName();
    }
}
