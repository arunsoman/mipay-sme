package com.mipay.sme.web;

import com.mipay.sme.security.rbac.PublicEndpoint;
import com.mipay.sme.service.OnboardingService;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self onboarding (UAT SB p.8): TPTS approval happens in the ops console. */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PublicEndpoint(reason = "self-register a merchant - public onboarding entry point")
    @PostMapping("/merchant")
    public Dtos.Message registerMerchant(@Valid @RequestBody Dtos.RegisterMerchantRequest req) {
        return onboardingService.registerMerchant(req);
    }

    @PublicEndpoint(reason = "self-register a supplier - public onboarding entry point")
    @PostMapping("/supplier")
    public Dtos.Message registerSupplier(@Valid @RequestBody Dtos.RegisterSupplierRequest req) {
        return onboardingService.registerSupplier(req);
    }
}
