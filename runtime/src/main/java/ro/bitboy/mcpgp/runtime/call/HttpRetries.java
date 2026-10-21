package ro.bitboy.mcpgp.runtime.call;

/**
 * The JDK HTTP client's own retries (tools-contract §6). It sends a GET or HEAD a second time, once,
 * when the target closed the connection without answering, which nothing can turn off and HTTP allows
 * for those methods (RFC 9110 §9.2.2). Its retry after a refused connection is turned off here.
 */
public final class HttpRetries {

    private HttpRetries() {
    }

    /**
     * Turns off the retry after a refused connection. The client reads the setting once, at its first
     * request in the process, so this runs at startup; an operator's own setting wins.
     */
    public static void noConnectRetry() {
        if (System.getProperty("jdk.httpclient.disableRetryConnect") == null) {
            System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        }
    }
}
