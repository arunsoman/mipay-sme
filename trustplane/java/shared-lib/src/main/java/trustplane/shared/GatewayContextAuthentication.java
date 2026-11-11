package trustplane.shared;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-authenticated principal resolved from the gateway's signed context
 * header. The MAC over the header (mesh mTLS + shared secret) is the
 * credential; authorities come from the permission list the gateway resolved
 * at the edge (RBAC ListPermissions) plus the OAuth scopes.
 */
public class GatewayContextAuthentication extends AbstractAuthenticationToken {
    private final GatewayContext ctx;

    public GatewayContextAuthentication(GatewayContext ctx) {
        super(authorities(ctx));
        this.ctx = ctx;
        super.setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(GatewayContext ctx) {
        List<GrantedAuthority> out = new ArrayList<>();
        for (String p : ctx.perms()) out.add(new SimpleGrantedAuthority(p));
        for (String s : ctx.scopes()) out.add(new SimpleGrantedAuthority("SCOPE_" + s));
        return out;
    }

    public GatewayContext context() { return ctx; }

    @Override
    public Object getCredentials() { return "gateway-context-mac"; }

    @Override
    public Object getPrincipal() { return ctx; }

    @Override
    public String getName() { return ctx.sub(); }
}
