package com.mipay.sme.security.auth;

import com.mipay.sme.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves the configured credential provider and validates the cutover switches at startup. */
@Component
public final class AuthProviderRegistry {
    private final Map<String, AuthPluginContracts.AuthProvider> providers = new HashMap<>();
    private final AppProperties properties;

    public AuthProviderRegistry(List<AuthPluginContracts.AuthProvider> available, AppProperties properties) {
        this.properties = properties;
        for (var provider : available) {
            if (providers.putIfAbsent(provider.key(), provider) != null) {
                throw new IllegalStateException("duplicate auth provider: " + provider.key());
            }
        }
        if (!List.of("legacy", "gateway").contains(properties.getIdentityMode())) {
            throw new IllegalStateException("app.identity-mode must be legacy or gateway");
        }
        if (!List.of("none", "trustplane").contains(properties.getAuthProvider())) {
            throw new IllegalStateException("app.auth-provider must be none or trustplane");
        }
        if (properties.getIdentityMode().equals("gateway") && !properties.getAuthProvider().equals("trustplane")) {
            throw new IllegalStateException("gateway identity requires the trustplane auth provider");
        }
        if (properties.getAuthProvider().equals("trustplane")) {
            if (!providers.containsKey("trustplane")) throw new IllegalStateException("trustplane provider is missing");
            URI uri = URI.create(properties.getAuthUrl());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException("app.auth-url must be an HTTPS IdP URL");
            }
            URI issuer = URI.create(properties.getAuthIssuer());
            if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null) {
                throw new IllegalStateException("app.auth-issuer must be HTTPS");
            }
        }
    }

    public AuthPluginContracts.AuthProvider selected() {
        var provider = providers.get(properties.getAuthProvider());
        if (provider == null) throw new IllegalStateException("credential provider is disabled");
        return provider;
    }

    public boolean enabled() { return !properties.getAuthProvider().equals("none"); }
}
