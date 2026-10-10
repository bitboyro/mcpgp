package ro.bitboy.mcpgp.runtime.http;

import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import reactor.core.publisher.Mono;

import java.util.List;

/** The Streamable HTTP transport, offering only the MCP revisions the manifest supports. */
final class RevisionLimitedTransport implements McpStreamableServerTransportProvider {

    private final McpStreamableServerTransportProvider delegate;
    private final List<String> revisions;

    RevisionLimitedTransport(McpStreamableServerTransportProvider delegate, List<String> revisions) {
        this.delegate = delegate;
        this.revisions = List.copyOf(revisions);
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(sessionFactory);
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return revisions;
    }
}
