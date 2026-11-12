package trustplane.comms;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for the Brevo transactional-email adapter (comms-design.md §8/B1). */
class BrevoEmailSenderTest {

    private record Recorded(int[] status, String url, String apiKey, String body) { }

    @Test
    void sendMapsSuccessToTheProviderMessageId() throws Exception {
        Recorded[] seen = new Recorded[1];
        BrevoEmailSender sender = new BrevoEmailSender("k-123", "no-reply@mipay.na", "MiPay",
                "https://api.brevo.test/v3/smtp/email", (url, apiKey, body) -> {
                    seen[0] = new Recorded(new int[1], url, apiKey, body);
                    return new BrevoEmailSender.HttpReply(201, "{\"messageId\":\"<201@smtp-relay>\"}");
                });
        String id = sender.send("user@example.na", "A. Name", "Set up your MiPay account",
                "Hello,\n\nSet your password here:\nhttps://x/link");
        assertEquals("brevo:<201@smtp-relay>", id);
        Recorded r = seen[0];
        assertEquals("https://api.brevo.test/v3/smtp/email", r.url());
        assertEquals("k-123", r.apiKey());
        assertTrue(r.body().contains("\"email\":\"user@example.na\""));
        assertTrue(r.body().contains("\"sender\":{\"name\":\"MiPay\",\"email\":\"no-reply@mipay.na\"}"),
                "sender block");
        assertTrue(r.body().contains("Set your password here:"));
        // the text part must contain the raw body; the HTML part escapes angle brackets
        assertTrue(r.body().contains("textContent"));
        assertTrue(r.body().contains("&lt;") ? true : r.body().contains("<html>"), "html part present");
    }

    @Test
    void providerRefusalThrowsSoTheWorkerRetries() {
        BrevoEmailSender sender = new BrevoEmailSender("k", "no-reply@mipay.na", "MiPay", "http://x",
                (url, apiKey, body) -> new BrevoEmailSender.HttpReply(429, "{\"message\":\"rate limited\"}"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> sender.send("user@example.na", null, "s", "b"));
        assertTrue(e.getMessage().contains("429"));
    }

    @Test
    void unreadableResponseStillCountsAsSuccess() throws Exception {
        BrevoEmailSender sender = new BrevoEmailSender("k", "no-reply@mipay.na", "MiPay", "http://x",
                (url, apiKey, body) -> new BrevoEmailSender.HttpReply(201, "not-json"));
        assertEquals("brevo", sender.send("a@b.c", null, "s", "b"));
    }

    @Test
    void escapeTreatsTemplateLinkAngleBracketsLiterally() {
        assertEquals("&lt;a&gt; &amp; &gt;", BrevoEmailSender.escape("<a> & >"));
    }
}
