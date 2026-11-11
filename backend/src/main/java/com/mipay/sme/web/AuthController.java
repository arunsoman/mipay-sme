package com.mipay.sme.web;

import com.mipay.sme.domain.core.User;
import com.mipay.sme.service.AuthService;
import com.mipay.sme.service.ActivationService;
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
    private final ActivationService activationService;

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
        // [H6] username wins when present; the phone field stays required
        String identifier = req.username() == null || req.username().isBlank()
                ? req.phone() : req.username();
        return authService.loginWithPassword(identifier, req.password());
    }

    /** Public activation completion ([C4/H8 L2]): token from the link, password chosen here. */
    @PostMapping("/activate")
    public Dtos.Message activate(@RequestBody Dtos.ActivateRequest req) {
        User u = activationService.activate(req.token(), req.password());
        return new Dtos.Message("Activation complete; sign in with phone or username", u.getUsername());
    }
}
