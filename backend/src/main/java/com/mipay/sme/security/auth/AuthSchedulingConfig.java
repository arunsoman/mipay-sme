package com.mipay.sme.security.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the scheduled outbox worker (and later the AuthSyncService nightly repair). */
@Configuration
@EnableScheduling
public class AuthSchedulingConfig { }
