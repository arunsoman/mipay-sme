package com.mipay.sme.web;

import com.mipay.sme.service.AuthService;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/otp")
    public Dtos.OtpResult requestOtp(@Valid @RequestBody Dtos.OtpRequest req) {
        return authService.requestOtp(req.phone());
    }

    @PostMapping("/otp/verify")
    public Dtos.TokenView verifyOtp(@Valid @RequestBody Dtos.OtpVerify req) {
        return authService.verifyOtp(req.phone(), req.code());
    }

    @PostMapping("/login")
    public Dtos.TokenView login(@Valid @RequestBody Dtos.PasswordLogin req) {
        return authService.loginWithPassword(req.phone(), req.password());
    }
}
