package com.mipay.sme.web;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public environment banner feed (E30): labels every screen with the SIMULATED rule. */
@RestController
@RequiredArgsConstructor
public class EnvironmentController {

    private final AppProperties props;

    @GetMapping("/api/env")
    public Dtos.EnvView env() {
        boolean simulated = props.isSimulatedEnvironment();
        return new Dtos.EnvView(props.getEnvironment(), simulated,
                simulated ? "SANDBOX/UAT - ALL BANK AND LENDER EVENTS ARE SIMULATED (UAT SB p.16)" :
                        "PRODUCTION");
    }
}
