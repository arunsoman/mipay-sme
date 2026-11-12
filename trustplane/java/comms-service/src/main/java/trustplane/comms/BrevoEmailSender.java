package trustplane.comms;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The B1 real email provider adapter (comms-design.md §8 "Email": a provider API rather
 * than raw SMTP). The core's templates render body + subject; this class only transports.
 *
 * <p>Sandbox delivery is NOT here: the worker keeps writing sim_outbox so UAT can read
 * codes and links (H8/M8). A real provider replaces the sandbox write for that channel —
 * the recipient gets the message in a real inbox, the sandbox record stays empty.</p>
 *
 * <p>Delivery is at-least-once (Brevo has no idempotency key on transactional email):
 * a lease retry after a lost response may send twice, exactly like the design promises.</p>
 */
public final class BrevoEmailSender implements EmailSender {

    /** The one HTTP call the provider needs; injected so tests never open sockets. */
    public interface Transport {
        HttpReply post(String url, String apiKey, String body) throws Exception;
    }

    public record HttpReply(int status, String body) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String apiKey;
    private final String fromEmail;
    private final String fromName;
    private final String url;
    private final Transport transport;

    BrevoEmailSender(String apiKey, String fromEmail, String fromName, String url, Transport transport) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.url = url;
        this.transport = transport;
    }

    /** Builds the sender from COMMS_BREVO_* environment variables; null when unconfigured. */
    static BrevoEmailSender create(CommsConfig.Settings settings) {
        if (!"brevo".equals(settings.providers().get("email"))) return null;
        String key = CommsConfig.env("COMMS_BREVO_API_KEY", "");
        String from = CommsConfig.env("COMMS_BREVO_FROM_EMAIL", "");
        if (key.isBlank() || from.isBlank())
            throw new IllegalStateException("email provider brevo needs COMMS_BREVO_API_KEY and COMMS_BREVO_FROM_EMAIL");
        String name = CommsConfig.env("COMMS_BREVO_FROM_NAME", "MiPay");
        String url = CommsConfig.env("COMMS_BREVO_URL", "https://api.brevo.com/v3/smtp/email");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new BrevoEmailSender(key, from, name, url, (u, k, body) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(u))
                    .timeout(Duration.ofSeconds(15))
                    .header("api-key", k).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpReply(response.statusCode(), response.body());
        });
    }

    @Override
    public String send(String to, String toName, String subject, String body) throws Exception {
        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", fromName, "email", fromEmail),
                "to", new Object[]{Map.of("email", to, "name", toName == null ? "" : toName)},
                "subject", subject,
                "textContent", body,
                "htmlContent", "<html><body style=\"font-family:sans-serif\">"
                        + java.util.Arrays.stream(body.split("\n"))
                        .map(BrevoEmailSender::escape).reduce((a, b) -> a + "<br>" + b).orElse("")
                        + "</body></html>");
        HttpReply reply = transport.post(url, apiKey, JSON.writeValueAsString(payload));
        if (reply.status() < 200 || reply.status() >= 300)
            throw new IllegalStateException("brevo email refused: HTTP " + reply.status());
        try {
            String id = JSON.readTree(reply.body()).path("messageId").asString(null);
            return id == null || id.isBlank() ? "brevo" : "brevo:" + id;
        } catch (Exception e) {
            return "brevo";
        }
    }

    static String escape(String line) {
        return line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
