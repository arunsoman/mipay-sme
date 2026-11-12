package trustplane.comms;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
@RequestMapping("/comms/v1")
public class CommsController {
    private final CommsService service;
    private final JdbcTemplate db;
    public CommsController(CommsService service, JdbcTemplate db) { this.service = service; this.db = db; }

    @GetMapping("/healthz")
    Map<String,String> health() {
        db.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ok");
    }

    static String caller(HttpServletRequest request) {
        Object value = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(value instanceof X509Certificate[] certs) || certs.length == 0)
            throw CommsService.bad(HttpStatus.UNAUTHORIZED, "mesh certificate required");
        String cn = certs[0].getSubjectX500Principal().getName();
        for (String part : cn.split(",")) if (part.strip().startsWith("CN=")) {
            String caller = part.strip().substring(3).toLowerCase();
            if (caller.startsWith("sme-")) caller = caller.substring(4);
            if (caller.endsWith("-mesh")) caller = caller.substring(0, caller.length() - 5);
            return caller;
        }
        throw CommsService.bad(HttpStatus.UNAUTHORIZED, "mesh caller unknown");
    }

    @PostMapping("/messages")
    ResponseEntity<Map<String,Object>> send(@RequestBody CommsService.Send body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem,
            HttpServletRequest request) throws Exception {
        return ResponseEntity.accepted().body(service.send(caller(request), body, idem));
    }

    @GetMapping("/requests/{id}")
    Map<String,Object> request(@PathVariable String id, HttpServletRequest request) {
        return service.request(caller(request), id);
    }

    @GetMapping("/messages/{id}")
    Map<String,Object> message(@PathVariable String id, HttpServletRequest request) {
        return service.message(caller(request), id);
    }

    @GetMapping("/messages/{id}/events")
    Map<String,Object> events(@PathVariable String id, HttpServletRequest request) {
        return service.events(caller(request), id);
    }

    @PutMapping("/subjects/{ref}/contacts")
    Map<String,Object> putContacts(@PathVariable String ref, @RequestBody CommsService.Contacts body,
            HttpServletRequest request) throws Exception {
        return service.putContacts(caller(request), ref, body);
    }

    @GetMapping("/subjects/{ref}/contacts")
    Map<String,Object> contacts(@PathVariable String ref, HttpServletRequest request) {
        return service.contacts(caller(request), ref);
    }

    @GetMapping("/capabilities")
    Map<String,Object> capabilities(HttpServletRequest request) {
        caller(request);
        return service.capabilities();
    }

    @GetMapping("/sandbox/outbox")
    Map<String,Object> outbox(@RequestParam(defaultValue = "") String recipient,
            @RequestParam(defaultValue = "") String event, HttpServletRequest request) {
        return service.sandboxOutbox(caller(request), recipient, event);
    }
}
