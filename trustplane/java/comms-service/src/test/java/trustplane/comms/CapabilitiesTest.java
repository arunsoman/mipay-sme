package trustplane.comms;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CapabilitiesTest {
    private final CommsConfig.Policy otp = new CommsConfig.Policy("SECURITY", "CRITICAL", List.of("sms"),
            java.time.Duration.ofMinutes(5), true, List.of("code"));

    @SuppressWarnings("unchecked")
    @Test void absentSimulatorNeverReportsSmsLive() throws Exception {
        var cfg = new CommsConfig.Settings("uat", Map.of("idp", List.of("auth.")), List.of("mipay"),
                Map.of(), Map.of("auth.otp.sms", otp), List.of(new CommsConfig.Template("auth.otp.sms", "sms", "en", "", "Code {{code}}")));
        var service = new CommsService(null, new Sealer(new byte[32]), cfg);
        var channels = (Map<String,Object>) service.capabilities().get("channels");
        assertEquals(false, ((Map<String,Object>) channels.get("sms")).get("live"));
        assertEquals(false, ((Map<String,Object>) channels.get("push")).get("live"));
    }

    @SuppressWarnings("unchecked")
    @Test void sandboxSmsIsLiveOnlyWithTemplate() throws Exception {
        var cfg = new CommsConfig.Settings("dev", Map.of("idp", List.of("auth.")), List.of("mipay"),
                Map.of("sms", "sandbox"), Map.of("auth.otp.sms", otp),
                List.of(new CommsConfig.Template("auth.otp.sms", "sms", "en", "", "Code {{code}}")));
        var service = new CommsService(null, new Sealer(new byte[32]), cfg);
        var channels = (Map<String,Object>) service.capabilities().get("channels");
        assertEquals(true, ((Map<String,Object>) channels.get("sms")).get("live"));
    }
}
