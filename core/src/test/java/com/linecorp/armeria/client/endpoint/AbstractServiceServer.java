package com.linecorp.armeria.client.endpoint;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;

import io.netty.util.internal.PlatformDependent;

public abstract class AbstractServiceServer {
    private Server server;

    protected abstract void configureServer(ServerBuilder sb) throws Exception;

    @SuppressWarnings("unchecked")
    public <T extends AbstractServiceServer> T start() throws Exception {
        ServerBuilder sb = new ServerBuilder().port(0, SessionProtocol.HTTP);
        configureServer(sb);
        server = sb.build();

        try {
            server.start().get();
        } catch (InterruptedException e) {
            PlatformDependent.throwException(e);
        }
        return (T) this;
    }

    public int port() {
        ServerPort port = server.activePort()
                                .orElseThrow(() -> new IllegalStateException("server not started yet"));
        return port.localAddress().getPort();
    }

    public void stop() {
        server.stop();
    }
}
