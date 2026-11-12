package trustplane.comms;

/**
 * A real message transport for one rendered message (comms-design.md B1). The worker owns
 * the sandbox path (sim_outbox, H8/M8); implementations of this interface take over a
 * channel when comms.yaml names a real provider for it.
 */
public interface EmailSender {

    /**
     * Sends one rendered message to one address. Returns a provider id recorded on the
     * message row; throws on failure (the worker retries, delivery is at-least-once).
     */
    String send(String to, String toName, String subject, String body) throws Exception;
}
