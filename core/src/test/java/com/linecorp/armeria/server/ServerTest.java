/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.metric.MicrometerUtil;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.BlockingUtils;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

@GenerateNativeImageTrace
class ServerTest {

    private static final long processDelayMillis = 1000;
    private static final long requestTimeoutMillis = 500;
    private static final long idleTimeoutMillis = 500;

    private static final EventExecutorGroup asyncExecutorGroup = new DefaultEventExecutorGroup(1);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.channelOption(ChannelOption.SO_BACKLOG, 1024);
            sb.meterRegistry(PrometheusMeterRegistries.newRegistry());

            final HttpService immediateResponseOnIoThread =
                    new EchoService().decorate(LoggingService.newDecorator());

            final HttpService delayedResponseOnIoThread = new EchoService() {
                @Override
                protected HttpResponse echo(AggregatedHttpRequest aReq) {
                    BlockingUtils.blockingRun(() -> Thread.sleep(processDelayMillis));
                    return super.echo(aReq);
                }
            }.decorate(LoggingService.newDecorator());

            final HttpService lazyResponseNotOnIoThread = new EchoService() {
                @Override
                protected HttpResponse echo(AggregatedHttpRequest aReq) {
                    final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                    final HttpResponse res = HttpResponse.of(responseFuture);
                    asyncExecutorGroup.schedule(
                            () -> super.echo(aReq), processDelayMillis, TimeUnit.MILLISECONDS)
                                      .addListener((Future<HttpResponse> future) ->
                                                           responseFuture.complete(future.getNow()));
                    return res;
                }
            }.decorate(LoggingService.newDecorator());

            final HttpService buggy = new AbstractHttpService() {
                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    throw Exceptions.clearTrace(new AnticipatedException("bug!"));
                }
            }.decorate(LoggingService.newDecorator());

            sb.service("/", immediateResponseOnIoThread)
              .service("/delayed", delayedResponseOnIoThread)
              .service("/timeout", lazyResponseNotOnIoThread)
              .service("/timeout-not", lazyResponseNotOnIoThread)
              .service("/buggy", buggy);

            // Disable request timeout for '/timeout-not' only.
            final Function<HttpService, HttpService> decorator =
                    s -> new SimpleDecoratingHttpService(s) {
                        @Override
                        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                            if ("/timeout-not".equals(ctx.path())) {
                                ctx.clearRequestTimeout();
                            } else {
                                ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, requestTimeoutMillis);
                            }
                            return unwrap().serve(ctx, req);
                        }
                    };

            sb.decorator(decorator);

            sb.idleTimeoutMillis(idleTimeoutMillis);
            // Enable access logs to make sure AccessLogWriter does not fail on an invalid path.
            sb.accessLogWriter(AccessLogWriter.common(), false);
        }
    };

    @AfterAll
    static void checkMetrics() {
        final MeterRegistry registry = server.server().meterRegistry();
        assertThat(MicrometerUtil.register(registry,
                                           new MeterIdPrefix("armeria.server.router.virtual.host.cache",
                                                             "hostname.pattern", "*"),
                                           Object.class, (r, i) -> null)).isNotNull();
    }

    /**
     * Ensures that the {@link Server} is always started when a test begins. This is necessary even if we
     * enabled auto-start for {@link ServerRule} because we stop it in {@link #testStartStop()}.
     */
    @BeforeEach
    void startServer() {
        server.start();
    }

    @Test
    void testStartStop() throws Exception {
        final Server server = ServerTest.server.server();
        assertThat(server.activePorts()).hasSize(1);
        server.stop().get();
        assertThat(server.activePorts()).isEmpty();
    }

    @Test
    void testInvocation() throws Exception {
        testInvocation0("/");
    }

    @Test
    void testDelayedResponseApiInvocationExpectedTimeout() throws Exception {
        testInvocation0("/delayed");
    }

    @Test
    void testChannelOptions() throws Exception {
        final ServerBootstrap bootstrap = server.server().serverBootstrap;
        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.config().options().get(ChannelOption.SO_BACKLOG)).isEqualTo(1024);
    }

    @Test
    void unsuccessfulStartupTerminatesBossGroup() {
        final Predicate<ThreadInfo> predicate = info -> {
            final String name = info.getThreadName();
            return name.startsWith("armeria-boss-") && name.endsWith(":" + server.httpPort());
        };

        // When one port is open, there should be only one boss group thread.
        final long oldNumBossThreads =
                Arrays.stream(ManagementFactory.getThreadMXBean().dumpAllThreads(false, false))
                      .filter(predicate)
                      .count();
        assertThat(oldNumBossThreads).isOne();

        // Attempt to start another server at the same port.
        final Server serverAtSamePort =
                Server.builder()
                      .http(server.httpPort())
                      .service("/", (ctx, req) -> HttpResponse.of(200))
                      .build();

        // .. which will fail with an IOException.
        assertThatThrownBy(() -> serverAtSamePort.start().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IOException.class);

        // A failed bind attempt must not leave a dangling boss group thread.
        final long numBossThreads =
                Arrays.stream(ManagementFactory.getThreadMXBean().dumpAllThreads(false, false))
                      .filter(predicate)
                      .count();
        assertThat(numBossThreads).isEqualTo(oldNumBossThreads);
    }

    private static void testInvocation0(String path) throws IOException, ParseException {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(server.httpUri().resolve(path));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, world!");
            }
        }
    }

    @Test
    void testRequestTimeoutInvocation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(server.httpUri() + "/timeout");
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(HttpStatusClass.valueOf(res.getCode())).isNotEqualTo(HttpStatusClass.SUCCESS);
            }
        }
    }

    @Test
    void testDynamicRequestTimeoutInvocation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(server.httpUri() + "/timeout-not");
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(HttpStatusClass.valueOf(res.getCode())).isEqualTo(HttpStatusClass.SUCCESS);
            }
        }
    }

    @Test
    void testIdleTimeoutByNoContentSent() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());
            final long connectedNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }
            final long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - connectedNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis).isGreaterThan((long) (idleTimeoutMillis * 0.9));
        }
    }

    @Test
    void testIdleTimeoutByContentSent() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());
            final PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);
            outWriter.print("POST / HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            final long lastWriteNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }

            final long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - lastWriteNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis).isGreaterThan((long) (idleTimeoutMillis * 0.9));
        }
    }

    /**
     * Ensure that the connection is not broken even if {@link Service#serve(ServiceRequestContext, Request)}
     * raises an exception.
     */
    @Test
    void testBuggyService() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());
            final PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);

            // Send a request to a buggy service whose invoke() raises an exception.
            // If the server handled the exception correctly (i.e. responded with 500 Internal Server Error and
            // recovered from the exception successfully), then the connection should not be closed immediately
            // but on the idle timeout of the second request.
            outWriter.print("POST /buggy HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("Content-Length: 0\r\n");
            outWriter.print("\r\n");
            outWriter.print("POST / HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            final long lastWriteNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }

            final long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - lastWriteNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis).isGreaterThan((long) (idleTimeoutMillis * 0.9));
        }
    }

    @Test
    void testOptions() throws Exception {
        testSimple("OPTIONS * HTTP/1.1", "HTTP/1.1 200 OK",
                   "allow: OPTIONS,GET,HEAD,POST,PUT,PATCH,DELETE,TRACE,CONNECT");
    }

    @Test
    void testInvalidPath() throws Exception {
        testSimple("GET * HTTP/1.1", "HTTP/1.1 400 Bad Request");
    }

    @Test
    void testUnsupportedMethod() throws Exception {
        testSimple("WHOA / HTTP/1.1", "HTTP/1.1 405 Method Not Allowed");
    }

    @Test
    void duplicatedPort() {
        // Known to fail on WSL (Windows Subsystem for Linux)
        assumeThat(System.getenv("WSLENV")).isNull();

        final Server duplicatedPortServer = Server.builder()
                                                  .http(server.httpPort())
                                                  .service("/", (ctx, res) -> HttpResponse.of(""))
                                                  .build();
        assertThatThrownBy(() -> duplicatedPortServer.start().join())
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void testActiveLocalPort() throws Exception {
        final Server server = Server.builder()
                                    .http(0)
                                    .https(0)
                                    .tlsSelfSigned()
                                    .service("/", (ctx, res) -> HttpResponse.of(""))
                                    .build();

        // not started yet
        assertThatThrownBy(server::activeLocalPort)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active local ports");

        server.start().get();

        assertThat(server.activeLocalPort()).isPositive();
        assertThat(server.activeLocalPort(SessionProtocol.HTTP)).isPositive();
        assertThat(server.activeLocalPort(SessionProtocol.HTTPS)).isPositive();
        assertThatThrownBy(() -> server.activeLocalPort(SessionProtocol.PROXY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active local ports for " + SessionProtocol.PROXY);
    }

    @Test
    void defaultStartStopExecutor() {
        final Server server = ServerTest.server.server();
        final Queue<Thread> threads = new LinkedTransferQueue<>();
        server.addListener(new ThreadRecordingServerListener(threads));

        threads.add(server.stop().thenApply(unused -> Thread.currentThread()).join());
        threads.add(server.start().thenApply(unused -> Thread.currentThread()).join());

        threads.forEach(t -> assertThat(t.getName()).startsWith("startstop-support"));
    }

    @Test
    void customStartStopExecutor() {
        final Queue<Thread> threads = new LinkedTransferQueue<>();
        final String prefix = getClass().getName() + "#customStartStopExecutor";

        final AtomicBoolean serverStarted = new AtomicBoolean();
        final ThreadFactory factory = ThreadFactories.builder(prefix).taskFunction(task -> () -> {
            await().untilFalse(serverStarted);
            task.run();
        }).build();

        final ExecutorService executor = Executors.newSingleThreadExecutor(factory);
        final Server server = Server.builder()
                                    .startStopExecutor(executor)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .serverListener(new ThreadRecordingServerListener(threads))
                                    .build();

        threads.add(server.start().thenApply(unused -> Thread.currentThread()).join());
        serverStarted.set(true);

        final CompletableFuture<Thread> stopFuture = server.stop().thenApply(
                unused -> Thread.currentThread());
        serverStarted.set(false);
        threads.add(stopFuture.join());

        threads.forEach(t -> assertThat(t.getName()).startsWith(prefix));
    }

    @Test
    void gracefulShutdownBlockingTaskExecutor() {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        final Server server = Server.builder()
                                    .blockingTaskExecutor(executor, true)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();

        server.start().join();

        executor.execute(() -> {
            try {
                Thread.sleep(processDelayMillis * 2);
            } catch (InterruptedException ignored) {
                // Ignored
            }
        });

        server.stop().join();

        assertThat(server.config().blockingTaskExecutor().isShutdown()).isTrue();
        assertThat(server.config().blockingTaskExecutor().isTerminated()).isTrue();
    }

    @Test
    void notGracefulShutdownBlockingTaskExecutor() {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        final Server server = Server.builder()
                                    .blockingTaskExecutor(executor, false)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();

        server.start().join();

        executor.execute(() -> {
            try {
                Thread.sleep(processDelayMillis * 2);
            } catch (InterruptedException ignored) {
                // Ignored
            }
        });

        server.stop().join();

        assertThat(server.config().blockingTaskExecutor().isShutdown()).isFalse();
        assertThat(server.config().blockingTaskExecutor().isTerminated()).isFalse();
        assertThat(MoreExecutors.shutdownAndAwaitTermination(executor, 10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void versionMetrics() {
        final Server server = ServerTest.server.server();
        server.setupVersionMetrics();

        final MeterRegistry meterRegistry = server.config().meterRegistry();
        final Gauge gauge = meterRegistry.find("armeria.build.info")
                                         .tagKeys("version", "commit", "repo.status")
                                         .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isOne();
    }

    @Test
    void blockUntilShutdown() throws Exception {
        final AtomicBoolean stopped = new AtomicBoolean();
        final Server server = Server.builder()
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .serverListener(new ServerListenerAdapter() {
                                        @Override
                                        public void serverStopping(Server server) throws Exception {
                                            stopped.set(true);
                                        }
                                    })
                                    .build();
        server.start().join();
        CommonPools.blockingTaskExecutor().schedule(server::close, 1, TimeUnit.SECONDS);
        server.blockUntilShutdown();
        assertThat(stopped).isTrue();
    }

    private static void testSimple(
            String reqLine, String expectedStatusLine, String... expectedHeaders) throws Exception {

        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server.httpSocketAddress());
            final PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);

            outWriter.print(reqLine);
            outWriter.print("\r\n");
            outWriter.print("Connection: close\r\n");
            outWriter.print("Content-Length: 0\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));

            assertThat(in.readLine()).isEqualTo(expectedStatusLine);
            // Read till the end of the connection.
            final List<String> headers = new ArrayList<>();
            for (;;) {
                final String line = in.readLine();
                if (line == null) {
                    break;
                }

                // This is not really correct, but just wanna make it as simple as possible.
                headers.add(line);
            }

            for (String expectedHeader : expectedHeaders) {
                if (!headers.contains(expectedHeader)) {
                    Assertions.fail("does not contain '" + expectedHeader + "': " + headers);
                }
            }
        }
    }

    private static class EchoService extends AbstractHttpService {
        @Override
        protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate()
                                      .thenApply(this::echo)
                                      .exceptionally(CompletionActions::log));
        }

        protected HttpResponse echo(AggregatedHttpRequest aReq) {
            return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), aReq.content());
        }
    }

    private static class ThreadRecordingServerListener implements ServerListener {
        private final Queue<Thread> threads;

        ThreadRecordingServerListener(Queue<Thread> threads) {
            this.threads = requireNonNull(threads, "threads");
        }

        @Override
        public void serverStarting(Server server) {
            recordThread();
        }

        @Override
        public void serverStarted(Server server) {
            recordThread();
        }

        @Override
        public void serverStopping(Server server) {
            recordThread();
        }

        @Override
        public void serverStopped(Server server) {
            recordThread();
        }

        private void recordThread() {
            threads.add(Thread.currentThread());
        }
    }
}
