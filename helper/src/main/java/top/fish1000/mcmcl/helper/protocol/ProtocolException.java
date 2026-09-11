package top.fish1000.mcmcl.helper.protocol;

/** Indicates malformed JSON or a request that violates the helper protocol. */
public final class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
