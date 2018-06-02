package com.linecorp.armeria.retrofit2.shared;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;

@State(Scope.Benchmark)
public abstract class SimpleBenchmarkBase {

    private Server server;

    @Setup
    public void start() throws Exception {
        server = new ServerBuilder()
                .https(0)
                .service("/empty", (ctx, req) -> HttpResponse.of("\"\""))
                .tlsSelfSigned()
                .build();
        server.start().join();
    }

    @TearDown
    public void stop() {
        server.stop().join();
    }

    protected abstract SimpleBenchmarkClient client() throws Exception;

    protected String baseUrl() {
        final ServerPort httpPort = server.activePorts().values().stream()
                                          .filter(ServerPort::hasHttps).findAny()
                                          .get();
        return "https://localhost:" + httpPort.localAddress().getPort();
    }

    @Benchmark
    public void empty() throws Exception {
        client().empty().join();
    }
}
