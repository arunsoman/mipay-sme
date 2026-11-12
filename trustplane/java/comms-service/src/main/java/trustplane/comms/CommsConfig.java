package trustplane.comms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

/** Validated subset of the shared comms.yaml used by the mesh B0 API. */
@Configuration
public class CommsConfig {
    public record Policy(String category, String lane, List<String> channels, Duration ttl,
                         boolean requireExpiry, List<String> secret) {}
    public record Template(String event, String channel, String locale, String subject, String body) {}
    public record Settings(String environment, Map<String, List<String>> callers, List<String> contactWriters,
                           Map<String, String> providers, Map<String, Policy> policies,
                           List<Template> templates) {
        boolean sandbox() { return !"production".equals(environment); }
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    @Bean
    Settings settings() throws Exception {
        String environment = env("COMMS_ENVIRONMENT", "dev");
        if (!List.of("dev", "sandbox", "uat", "production").contains(environment))
            throw new IllegalArgumentException("unknown COMMS_ENVIRONMENT: " + environment);
        Map<String, Object> cfg = new Yaml().load(Files.readString(Path.of(env("COMMS_CONFIG", "/config/comms/comms.yaml"))));
        Map<String, List<String>> callers = new HashMap<>();
        ((Map<String, Object>) cfg.get("callers")).forEach((key, value) -> callers.put(key, strings(value)));
        Map<String, String> providers = new HashMap<>();
        ((Map<String, Map<String, String>>) cfg.get("providers")).forEach((channel, value) -> {
            String kind = value.get("kind");
            // B1: email may name the Brevo transactional API (COMMS_BREVO_*); every other
            // channel is still sandbox-only in B0
            List<String> allowed = "email".equals(channel) ? List.of("sandbox", "brevo")
                    : List.of("sandbox");
            if (!List.of("sms", "email", "push", "whatsapp", "in_app").contains(channel)
                    || !allowed.contains(kind))
                throw new IllegalArgumentException("unsupported comms provider: " + channel + "/" + kind);
            if ("production".equals(environment) && "sandbox".equals(kind))
                throw new IllegalArgumentException("sandbox provider cannot run in production");
            providers.put(channel, kind);
        });
        Map<String, Policy> policies = new HashMap<>();
        ((Map<String, Map<String, Object>>) cfg.get("event_policies")).forEach((event, raw) -> {
            String ttl = String.valueOf(raw.getOrDefault("ttl", "0s"));
            Duration duration = Duration.ofSeconds(Long.parseLong(ttl.substring(0, ttl.length() - 1)) *
                    switch (ttl.charAt(ttl.length() - 1)) { case 'm' -> 60; case 'h' -> 3600; case 'd' -> 86400; default -> 1; });
            policies.put(event, new Policy(String.valueOf(raw.get("category")), String.valueOf(raw.get("lane")),
                    strings(raw.get("channels")), duration, Boolean.TRUE.equals(raw.get("require_expiry")), strings(raw.get("secret"))));
        });
        List<Template> templates = new ArrayList<>();
        for (Map<String, Object> raw : (List<Map<String, Object>>) cfg.get("templates"))
            templates.add(new Template(String.valueOf(raw.get("event")), String.valueOf(raw.get("channel")),
                    String.valueOf(raw.getOrDefault("locale", "en")), String.valueOf(raw.getOrDefault("subject", "")),
                    String.valueOf(raw.get("body"))));
        Settings settings = new Settings(environment, callers, strings(cfg.get("contact_writers")), providers, policies, templates);
        validate(settings);
        return settings;
    }

    private static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("expected YAML list");
        return list.stream().map(String::valueOf).toList();
    }

    static void validate(Settings settings) {
        for (var entry : settings.policies.entrySet()) {
            Policy p = entry.getValue();
            if (!List.of("CRITICAL", "TRANSACTIONAL", "BULK").contains(p.lane()) || p.channels().isEmpty())
                throw new IllegalArgumentException("invalid policy for " + entry.getKey());
            for (String channel : p.channels()) {
                if (!settings.providers.containsKey(channel)) continue;
                if (settings.templates.stream().noneMatch(t -> t.event().equals(entry.getKey()) && t.channel().equals(channel)))
                    throw new IllegalArgumentException("missing template for " + entry.getKey() + "/" + channel);
            }
        }
    }
}
