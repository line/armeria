/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.internal.common.util.ChannelUtilTest.TCP_USER_TIMEOUT_BUFFER_MILLIS;
import static com.linecorp.armeria.server.ServerBuilder.MIN_PING_INTERVAL_MILLIS;
import static io.netty.channel.ChannelOption.SO_LINGER;
import static io.netty.channel.epoll.EpollChannelOption.TCP_USER_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.common.util.MinifiedBouncyCastleProvider;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.core.scheduler.Schedulers;

class ServerBuilderTest {

    private static final String RESOURCE_PATH_PREFIX =
            "/testing/core/" + ServerBuilderTest.class.getSimpleName() + '/';

    private static ClientFactory clientFactory;

    private static final AtomicInteger poppedRouterCnt = new AtomicInteger();
    private static final Supplier<? extends AutoCloseable> contextHookRouter = () ->
            (AutoCloseable) poppedRouterCnt::getAndIncrement;

    private static final AtomicInteger poppedRouterCnt2 = new AtomicInteger();
    private static final Supplier<? extends AutoCloseable> contextHookRouter2 = () ->
            (AutoCloseable) poppedRouterCnt2::getAndIncrement;

    private static final AtomicInteger poppedCnt = new AtomicInteger();
    private static final Supplier<? extends AutoCloseable> contextHook = () ->
            (AutoCloseable) poppedCnt::getAndIncrement;

    @RegisterExtension
    static final SelfSignedCertificateExtension selfSignedCertificate = new SelfSignedCertificateExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/test", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator((delegate, ctx, req) -> {
                  ctx.mutateAdditionalResponseHeaders(
                          mutator -> mutator.add("global_decorator", "true"));
                  return delegate.serve(ctx, req);
              })
              .virtualHost("*.example.com")
              .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator((delegate, ctx, req) -> {
                  ctx.mutateAdditionalResponseHeaders(
                          mutator -> mutator.add("virtualhost_decorator", "true"));
                  return delegate.serve(ctx, req);
              });
            sb.route()
              .path("/hook_route")
              .contextHook(contextHookRouter)
              .contextHook(contextHookRouter2)
              .build((ctx, req) -> HttpResponse.of("hook_route"));
        }
    };

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.contextHook(contextHook).service("/hook", (ctx, req) -> HttpResponse.of("hook"));
        }
    };

    @BeforeAll
    static void init() {
        clientFactory = ClientFactory.builder()
                                     .addressResolverGroupFactory(group -> MockAddressResolverGroup.localhost())
                                     .build();
    }

    @AfterAll
    static void destroy() {
        clientFactory.closeAsync();
    }

    private static Server newServerWithKeepAlive(long idleTimeoutMillis, long pingIntervalMillis) {
        return Server.builder()
                     .service("/", (ctx, req) -> HttpResponse.of(200))
                     .idleTimeoutMillis(idleTimeoutMillis)
                     .pingIntervalMillis(pingIntervalMillis)
                     .build();
    }

    @Test
    void acceptDuplicatePort() throws Exception {
        final Server server = Server.builder()
                                    .http(8080)
                                    .https(8080)
                                    .tlsSelfSigned()
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .build();

        final List<ServerPort> ports = server.config().ports();
        assertThat(ports.size()).isOne(); // merged
        assertThat(ports.get(0).protocols())
                .contains(SessionProtocol.HTTP, SessionProtocol.HTTPS);
    }

    @Test
    void treatAsSeparatePortIfZeroIsSpecifiedManyTimes() throws Exception {
        final Server server = Server.builder()
                                    .http(0)
                                    .http(0)
                                    .https(0)
                                    .tlsSelfSigned()
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .build();

        final List<ServerPort> ports = server.config().ports();
        assertThat(ports.size()).isEqualTo(3);
        assertThat(ports.get(0).protocols()).containsOnly(SessionProtocol.HTTP);
        assertThat(ports.get(1).protocols()).containsOnly(SessionProtocol.HTTP);
        assertThat(ports.get(2).protocols()).containsOnly(SessionProtocol.HTTPS);
    }

    @Test
    void numMaxConnections() {
        final ServerBuilder sb = Server.builder();
        assertThat(sb.maxNumConnections()).isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * Makes sure each virtual host can have its custom logger name.
     */
    @Test
    void setAccessLoggerTest1() {
        final Server sb = Server.builder()
                                .http(8080)  // Used for virtual host mapping
                                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                .accessLogger(LoggerFactory.getLogger("default"))
                                .virtualHost("*.example.com")
                                .and()
                                .virtualHost("*.example2.com")
                                .accessLogger("com.ex2")
                                .and()
                                .virtualHost("*.example3.com")
                                .accessLogger(host -> LoggerFactory.getLogger("com.ex3"))
                                .and()
                                .virtualHost("def.example4.com", "*.example4.com")
                                .and()
                                .virtualHost("def.example5.com", "*.example5.com")
                                .accessLogger("com.ex5")
                                .and()
                                .virtualHost("port.example5.com", "*.example5.com:8080")
                                .accessLogger("port.ex5")
                                .and()
                                .build();
        assertThat(sb.config().defaultVirtualHost()).isNotNull();
        assertThat(sb.config().defaultVirtualHost().accessLogger().getName()).isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example.com", -1).accessLogger().getName())
                .isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example2.com", -1).accessLogger().getName())
                .isEqualTo("com.ex2");

        assertThat(sb.config().findVirtualHost("*.example3.com", -1).accessLogger().getName())
                .isEqualTo("com.ex3");

        assertThat(sb.config().findVirtualHost("*.example4.com", -1).accessLogger().getName())
                .isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example5.com", -1).accessLogger().getName())
                .isEqualTo("com.ex5");

        assertThat(sb.config().findVirtualHost("*.example5.com", 8080).accessLogger().getName())
                .isEqualTo("port.ex5");
    }

    /**
     * Makes sure that {@link VirtualHost}s can have a proper {@link Logger} used for writing access
     * when a user specifies the default access logger via {@link ServerBuilder#accessLogger(String)}.
     */
    @Test
    void setAccessLoggerTest2() {
        final Server sb = Server.builder()
                                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                .accessLogger("test.default")
                                .virtualHost("*.example.com")
                                .and()
                                .build();
        assertThat(sb.config().defaultVirtualHost().accessLogger().getName())
                .isEqualTo("test.default");
        assertThat(sb.config().findVirtualHost("*.example.com", -1).accessLogger().getName())
                .isEqualTo("test.default");
    }

    /**
     * Makes sure that {@link VirtualHost}s can have a proper {@link Logger} used for writing access
     * when a user doesn't specify it.
     */
    @Test
    void defaultAccessLoggerTest() {
        final Server sb = Server.builder()
                                .http(8080)
                                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                .virtualHost("*.example.com")
                                .and()
                                .virtualHost("*.example2.com")
                                .and()
                                .virtualHost("*.example2.com:8080")
                                .and()
                                .build();
        assertThat(sb.config().findVirtualHost("*.example.com", -1).accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example");
        assertThat(sb.config().findVirtualHost("*.example2.com", -1).accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example2");
        assertThat(sb.config().findVirtualHost("*.example2.com", 8080).accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example2:8080");
    }

    /**
     * Makes sure that {@link ServerBuilder#build()} throws {@link IllegalStateException}
     * when the access logger of a {@link VirtualHost} set by a user is {@code null}.
     */
    @Test
    void buildIllegalExceptionTest() {
        final ServerBuilder sb = Server.builder()
                                       .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                       .accessLogger(host -> null);
        assertThatThrownBy(sb::build).isInstanceOf(IllegalStateException.class);
        final ServerBuilder sb2 =
                Server.builder()
                      .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                      .accessLogger(host -> {
                          if ("*.example.com".equals(host.hostnamePattern())) {
                              return null;
                          }
                          return LoggerFactory.getLogger("default");
                      })
                      .virtualHost("*.example.com").and();
        assertThatThrownBy(sb2::build).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Makes sure that {@link ServerBuilder#decorator(DecoratingHttpServiceFunction)} works at every service and
     * virtual hosts and {@link VirtualHostBuilder#decorator(DecoratingHttpServiceFunction)} works only at
     * its own services.
     */
    @Test
    void decoratorTest() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/").aggregate().get();
        assertThat(res.headers().get("global_decorator")).isEqualTo("true");
        assertThat(res.headers().contains("virtualhost_decorator")).isEqualTo(false);
        final AggregatedHttpResponse res2 = client.get("/test").aggregate().get();
        assertThat(res2.headers().get("global_decorator")).isEqualTo("true");
        assertThat(res2.headers().contains("virtualhost_decorator")).isEqualTo(false);

        final WebClient vhostClient = WebClient.builder("http://test.example.com:" + server.httpPort())
                                               .factory(clientFactory)
                                               .build();
        final AggregatedHttpResponse res3 = vhostClient.get("/").aggregate().get();
        assertThat(res3.headers().get("global_decorator")).isEqualTo("true");
        assertThat(res3.headers().get("virtualhost_decorator")).isEqualTo("true");
    }

    @Test
    void serveWithDefaultVirtualHostServiceIfNotExists() {
        final Server server = Server.builder()
                                    .serviceUnder("/", (ctx, req) -> HttpResponse.of("default"))
                                    .service("/abc", (ctx, req) -> HttpResponse.of("default_abc"))
                                    .virtualHost("foo.com")
                                    .service("/", (ctx, req) -> HttpResponse.of("foo"))
                                    .service("/abc", (ctx, req) -> HttpResponse.of("foo_abc"))
                                    .and().build();
        server.start().join();

        final WebClient client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                                          .factory(clientFactory)
                                          .build();
        final WebClient fooClient = WebClient.builder("http://foo.com:" + server.activeLocalPort())
                                             .factory(clientFactory)
                                             .build();

        assertThat(client.get("/").aggregate().join().contentUtf8()).isEqualTo("default");
        assertThat(client.get("/abc").aggregate().join().contentUtf8()).isEqualTo("default_abc");

        assertThat(fooClient.get("/").aggregate().join().contentUtf8()).isEqualTo("foo");
        assertThat(fooClient.get("/abc").aggregate().join().contentUtf8()).isEqualTo("foo_abc");

        // should route to a service of default virtual host
        assertThat(fooClient.get("/unknown").aggregate().join().contentUtf8()).isEqualTo("default");
    }

    @Test
    void serviceConfigurationPriority() {
        final Server server = Server.builder()
                                    .requestTimeoutMillis(100)     // for default virtual host
                                    .service("/default_virtual_host",
                                             (ctx, req) -> HttpResponse.delayed(
                                                     HttpResponse.of(HttpStatus.OK),
                                                     Duration.ofMillis(200),
                                                     ctx.eventLoop()))
                                    .withRoute(
                                            r -> r.get("/service_config")
                                                  .requestTimeoutMillis(200)     // for service
                                                  .build((ctx, req) -> HttpResponse.delayed(
                                                          HttpResponse.of(HttpStatus.OK),
                                                          Duration.ofMillis(250),
                                                          ctx.eventLoop())))
                                    .withVirtualHost(
                                            "foo.com",
                                            h -> h.service("/custom_virtual_host",
                                                           (ctx, req) -> HttpResponse.delayed(
                                                                   HttpResponse.of(HttpStatus.OK),
                                                                   Duration.ofMillis(150),
                                                                   ctx.eventLoop()))
                                                  .requestTimeoutMillis(300))    // for custom virtual host
                                    .build();
        server.start().join();

        try {
            final WebClient client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                                              .factory(clientFactory)
                                              .build();
            final WebClient fooClient = WebClient.builder("http://foo.com:" + server.activeLocalPort())
                                                 .factory(clientFactory)
                                                 .build();

            assertThat(client.get("/default_virtual_host").aggregate().join().status())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(client.get("/service_config").aggregate().join().status())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            // choose from 'foo.com' virtual host
            assertThat(fooClient.get("/default_virtual_host").aggregate().join().status())
                    .isEqualTo(HttpStatus.OK);
            // choose from service config
            assertThat(fooClient.get("/service_config").aggregate().join().status())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            // choose from 'foo.com' virtual host
            assertThat(fooClient.get("/custom_virtual_host").aggregate().join().status())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            server.stop();
        }
    }

    @Test
    void tlsCustomizationWithFile() {
        tlsCustomization(sb -> sb.tls(selfSignedCertificate.certificateFile(),
                                      selfSignedCertificate.privateKeyFile()),
                         vhb -> vhb.tls(selfSignedCertificate.certificateFile(),
                                        selfSignedCertificate.privateKeyFile()));
    }

    @Test
    void tlsCustomizationWithInputStream() {
        tlsCustomization(sb -> {
                             try {
                                 sb.tls(new FileInputStream(selfSignedCertificate.certificateFile()),
                                        new FileInputStream(selfSignedCertificate.privateKeyFile()));
                             } catch (FileNotFoundException e) {
                                 throw new AssertionError(e);
                             }
                         },
                         vhb -> {
                             try {
                                 vhb.tls(new FileInputStream(selfSignedCertificate.certificateFile()),
                                         new FileInputStream(selfSignedCertificate.privateKeyFile()));
                             } catch (FileNotFoundException e) {
                                 throw new AssertionError(e);
                             }
                         });
    }

    @Test
    void tlsCustomizationWithKeyObjects() {
        tlsCustomization(sb -> sb.tls(selfSignedCertificate.privateKey(),
                                      selfSignedCertificate.certificate()),
                         vhb -> vhb.tls(selfSignedCertificate.privateKey(),
                                        selfSignedCertificate.certificate()));
    }

    @Test
    void tlsCustomizationWithTlsSelfSigned() {
        tlsCustomization(ServerBuilder::tlsSelfSigned, VirtualHostBuilder::tlsSelfSigned);
    }

    private static void tlsCustomization(Consumer<ServerBuilder> defaultKeySetter,
                                         Consumer<VirtualHostBuilder> virtualHostKeySetter) {
        final AtomicReference<SslContextBuilder> defaultSslCtxBuilder = new AtomicReference<>();
        final AtomicReference<SslContextBuilder> virtualHostSslCtxBuilder = new AtomicReference<>();
        final AtomicInteger firstDefaultCustomizerInvoked = new AtomicInteger();
        final AtomicInteger secondDefaultCustomizerInvoked = new AtomicInteger();
        final AtomicInteger firstVirtualHostCustomizerInvoked = new AtomicInteger();
        final AtomicInteger secondVirtualHostCustomizerInvoked = new AtomicInteger();

        final ServerBuilder sb = Server.builder().service("/", (ctx, req) -> HttpResponse.of(200));
        defaultKeySetter.accept(sb);
        sb.tlsCustomizer(b -> {
            firstDefaultCustomizerInvoked.incrementAndGet();
            defaultSslCtxBuilder.set(b);
        });
        sb.tlsCustomizer(b -> {
            secondDefaultCustomizerInvoked.incrementAndGet();
            assertThat(b).isSameAs(defaultSslCtxBuilder.get());
        });

        // A virtual host must have its own self-signed certificate and customizers.
        // i.e. first and second customizer should not be invoked.
        final VirtualHostBuilder vhb = sb.virtualHost("example.com");
        virtualHostKeySetter.accept(vhb);
        vhb.tlsCustomizer(b -> {
            firstVirtualHostCustomizerInvoked.incrementAndGet();
            virtualHostSslCtxBuilder.set(b);
        });
        vhb.tlsCustomizer(b -> {
            secondVirtualHostCustomizerInvoked.incrementAndGet();
            assertThat(b).isSameAs(virtualHostSslCtxBuilder.get());
        });

        // No interaction should occur until `ServerBuilder.build()`.
        assertThat(firstDefaultCustomizerInvoked).hasValue(0);
        assertThat(secondDefaultCustomizerInvoked).hasValue(0);
        assertThat(firstVirtualHostCustomizerInvoked).hasValue(0);
        assertThat(secondVirtualHostCustomizerInvoked).hasValue(0);

        // Try to build twice, to make sure `build()` does not have any side effects.
        for (int i = 0; i < 2; i++) {
            sb.build();

            assertThat(firstDefaultCustomizerInvoked).hasValue(1);
            assertThat(secondDefaultCustomizerInvoked).hasValue(1);
            assertThat(firstVirtualHostCustomizerInvoked).hasValue(1);
            assertThat(secondVirtualHostCustomizerInvoked).hasValue(1);

            assertThat(defaultSslCtxBuilder.get()).isNotSameAs(virtualHostSslCtxBuilder.get());

            firstDefaultCustomizerInvoked.set(0);
            secondDefaultCustomizerInvoked.set(0);
            firstVirtualHostCustomizerInvoked.set(0);
            secondVirtualHostCustomizerInvoked.set(0);
        }
    }

    @Test
    void tlsCustomizerWithoutTls() {
        // Did not call `tls()` for both default host and virtual host.
        assertThatThrownBy(() -> Server.builder()
                                       .virtualHost("example.com")
                                       .tlsCustomizer(unused -> {})
                                       .and().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tlsCustomizer");

        // Called `tls()` for default host but did not for virtual host.
        assertThatThrownBy(() -> Server.builder()
                                       .tls(selfSignedCertificate.certificateFile(),
                                            selfSignedCertificate.privateKeyFile())
                                       .virtualHost("example.com")
                                       .tlsCustomizer(unused -> {})
                                       .and().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tlsCustomizer");
    }

    @Test
    void tlsCustomizerWithoutTlsSelfSigned() {
        // Called `tlsSelfSigned()` for default host but did not for virtual host.
        assertThatThrownBy(() -> Server.builder()
                                       .tlsSelfSigned()
                                       .virtualHost("example.com")
                                       .tlsCustomizer(unused -> {})
                                       .and().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tlsCustomizer");
    }

    @ParameterizedTest
    @CsvSource({ "pkcs5.pem", "pkcs8.pem" })
    void tlsPkcsPrivateKeys(String privateKeyFileName) {
        final String resourceRoot =
                '/' + MinifiedBouncyCastleProvider.class.getPackage().getName().replace('.', '/') + '/';
        Server.builder()
              .tls(getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "cert.pem"),
                   getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + privateKeyFileName))
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .build();
    }

    @ParameterizedTest
    @CsvSource({ "pkcs5.pem", "pkcs8.pem" })
    void tlsPkcsPrivateKeysWithCustomizer(String privateKeyFileName) {
        Server.builder()
              .tlsSelfSigned()
              .tlsCustomizer(sslCtxBuilder -> {
                  sslCtxBuilder.keyManager(
                          getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "cert.pem"),
                          getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + privateKeyFileName));
              })
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .build();
    }

    @Test
    void tlsEngineType() {
        final Server sb1 = Server.builder()
                                 .service("/example", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                 .build();
        assertThat(sb1.config().defaultVirtualHost().tlsEngineType()).isEqualTo(TlsEngineType.OPENSSL);

        final Server sb2 = Server.builder()
                                 .tlsSelfSigned()
                                 .service("/example", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                 .tlsEngineType(TlsEngineType.OPENSSL)
                                 .virtualHost("*.example1.com")
                                 .service("/example", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                 .tlsSelfSigned()
                                 .tlsEngineType(TlsEngineType.JDK)
                                 .and()
                                 .virtualHost("*.example2.com")
                                 .service("/example", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                 .tlsSelfSigned()
                                 .and()
                                 .virtualHost("*.example3.com")
                                 .service("/example", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                 .and()
                                 .build();
        assertThat(sb2.config().defaultVirtualHost().tlsEngineType()).isEqualTo(TlsEngineType.OPENSSL);
        assertThat(sb2.config().findVirtualHost("*.example1.com", 8080).tlsEngineType())
                .isEqualTo(TlsEngineType.JDK);
        assertThat(sb2.config().findVirtualHost("*.example2.com", 8080).tlsEngineType())
                .isEqualTo(TlsEngineType.OPENSSL);
    }

    @Test
    void monitorBlockingTaskExecutorAndSchedulersTogetherWithPrometheus() {
        final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        Metrics.addRegistry(registry);
        Server.builder()
              .meterRegistry(registry)
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .build();
        Schedulers.enableMetrics();
        Schedulers.decorateExecutorService(Schedulers.single(), Executors.newSingleThreadScheduledExecutor());
    }

    @Test
    void positivePingIntervalShouldBeGreaterThan1Second() {
        final ServerConfig config1 = newServerWithKeepAlive(15000, 0).config();
        assertThat(config1.idleTimeoutMillis()).isEqualTo(15000);
        assertThat(config1.pingIntervalMillis()).isEqualTo(0);

        assertThatThrownBy(() -> newServerWithKeepAlive(10000, MIN_PING_INTERVAL_MILLIS - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: >= " + MIN_PING_INTERVAL_MILLIS + " or == 0)");

        final ServerConfig config2 = newServerWithKeepAlive(15000, 15000).config();
        assertThat(config2.idleTimeoutMillis()).isEqualTo(15000);
        assertThat(config2.pingIntervalMillis()).isEqualTo(0);

        final ServerConfig config3 = newServerWithKeepAlive(15000, MIN_PING_INTERVAL_MILLIS).config();
        assertThat(config3.idleTimeoutMillis()).isEqualTo(15000);
        assertThat(config3.pingIntervalMillis()).isEqualTo(MIN_PING_INTERVAL_MILLIS);

        final ServerConfig config4 = newServerWithKeepAlive(20000, 19999).config();
        assertThat(config4.idleTimeoutMillis()).isEqualTo(20000);
        assertThat(config4.pingIntervalMillis()).isEqualTo(19999);
    }

    @CsvSource({
            "0,     10000, 10000",
            "15000, 20000, 0",
            "20000, 15000, 15000",
    })
    @ParameterizedTest
    void pingIntervalShouldBeLessThanIdleTimeout(long idleTimeoutMillis, long pingIntervalMillis,
                                                 long expectedPingIntervalMillis) {
        final ServerConfig config = newServerWithKeepAlive(idleTimeoutMillis, pingIntervalMillis).config();
        assertThat(config.idleTimeoutMillis()).isEqualTo(idleTimeoutMillis);
        assertThat(config.pingIntervalMillis()).isEqualTo(expectedPingIntervalMillis);
    }

    @CsvSource({
            "0,     10000",
            "15000, 0",
            "20000, 20000",
            "20000, 15000",
            "15000, 20000",
    })
    @ParameterizedTest
    void maxConnectionAgeShouldGreatOrEqualThanIdleTimeout(long idleTimeoutMillis,
                                                           long maxConnectionAgeMillis) {
        final ServerConfig config = Server.builder()
                                          .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                          .idleTimeoutMillis(idleTimeoutMillis)
                                          .maxConnectionAgeMillis(maxConnectionAgeMillis)
                                          .build()
                                          .config();

        if (maxConnectionAgeMillis > 0 &&
            (idleTimeoutMillis == 0 || idleTimeoutMillis > maxConnectionAgeMillis)) {
            assertThat(config.maxConnectionAgeMillis()).isEqualTo(maxConnectionAgeMillis);
            assertThat(config.idleTimeoutMillis()).isEqualTo(maxConnectionAgeMillis);
        } else {
            assertThat(config.idleTimeoutMillis()).isEqualTo(idleTimeoutMillis);
            assertThat(config.maxConnectionAgeMillis()).isEqualTo(maxConnectionAgeMillis);
        }
    }

    @Test
    void invalidMaxConnectionAge() {
        assertThatThrownBy(() -> Server.builder().maxConnectionAgeMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: >= 1000 or == 0");

        assertThatThrownBy(() -> Server.builder().maxConnectionAgeMillis(100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: >= 1000 or == 0");
    }

    @Test
    void defaultTcpUserTimeoutApplied() {
        assumeThat(Flags.transportType()).isEqualTo(TransportType.EPOLL);

        // default tcp user timeout applied
        final int lingerMillis = 1000;
        final int idleTimeoutMillis = 12_000;
        Server server = Server.builder()
                              .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                              .idleTimeoutMillis(idleTimeoutMillis)
                              .childChannelOption(SO_LINGER, lingerMillis)
                              .build();
        Map<ChannelOption<?>, Object> childChannelOptions =
                (Map<ChannelOption<?>, Object>) server.config().childChannelOptions();
        assertThat(childChannelOptions)
                .containsExactly(entry(TCP_USER_TIMEOUT, idleTimeoutMillis + TCP_USER_TIMEOUT_BUFFER_MILLIS),
                                 entry(SO_LINGER, lingerMillis));

        // user defined value is respected
        final int userDefinedValue = 30_000;
        server = Server.builder()
                       .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                       .idleTimeoutMillis(idleTimeoutMillis)
                       .childChannelOption(TCP_USER_TIMEOUT, userDefinedValue)
                       .childChannelOption(SO_LINGER, lingerMillis)
                       .build();
        childChannelOptions = (Map<ChannelOption<?>, Object>) server.config().childChannelOptions();
        assertThat(childChannelOptions)
                .containsExactly(entry(TCP_USER_TIMEOUT, userDefinedValue),
                                 entry(SO_LINGER, lingerMillis));
    }

    @Test
    void exceptionReportInterval() {
        final Server server1 = Server.builder()
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .unloggedExceptionsReportInterval(Duration.ofMillis(1000))
                                     .build();
        assertThat(server1.config().unloggedExceptionsReportIntervalMillis()).isEqualTo(1000);

        final Server server2 = Server.builder()
                                     .unloggedExceptionsReportInterval(Duration.ofMillis(0))
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .build();
        assertThat(server2.config().unloggedExceptionsReportIntervalMillis()).isZero();

        assertThrows(IllegalArgumentException.class, () ->
                Server.builder()
                      .unloggedExceptionsReportInterval(Duration.ofMillis(-1000))
                      .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                      .build());
    }

    @Test
    void exceptionReportIntervalMilliSeconds() {
        final Server server1 = Server.builder()
                                     .unloggedExceptionsReportIntervalMillis(1000)
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .build();
        assertThat(server1.config().unloggedExceptionsReportIntervalMillis()).isEqualTo(1000);

        final Server server2 = Server.builder()
                                     .unloggedExceptionsReportIntervalMillis(0)
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .build();
        assertThat(server2.config().unloggedExceptionsReportIntervalMillis()).isZero();

        assertThrows(IllegalArgumentException.class, () ->
                Server.builder()
                      .unloggedExceptionsReportIntervalMillis(-1000)
                      .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                      .build());
    }

    @Test
    void multipleDomainSocketAddresses() {
        final Server server = Server.builder()
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .http(DomainSocketAddress.of("/tmp/foo"))
                                    .http(DomainSocketAddress.of("/tmp/bar"))
                                    .https(DomainSocketAddress.of("/tmp/foo"))
                                    .https(DomainSocketAddress.of("/tmp/bar"))
                                    .tlsSelfSigned()
                                    .build();
        assertThat(server.config().ports()).containsExactly(
                new ServerPort(DomainSocketAddress.of("/tmp/foo"),
                               SessionProtocol.HTTP, SessionProtocol.HTTPS),
                new ServerPort(DomainSocketAddress.of("/tmp/bar"),
                               SessionProtocol.HTTP, SessionProtocol.HTTPS));
    }

    @Test
    void contextHook() {
        assertThat(poppedCnt.get()).isEqualTo(0);

        final WebClient client = WebClient.builder(server1.httpUri()).build();
        final AggregatedHttpResponse response = client.get("/hook").aggregate().join();

        assertThat(response.contentUtf8()).isEqualTo("hook");
        assertThat(poppedCnt.get()).isGreaterThan(0);
    }

    @Test
    void contextHook_route() {
        assertThat(poppedRouterCnt.get()).isEqualTo(0);
        assertThat(poppedRouterCnt2.get()).isEqualTo(0);

        final WebClient client = WebClient.builder(server.httpUri()).build();
        final AggregatedHttpResponse response = client.get("/hook_route").aggregate().join();

        assertThat(response.contentUtf8()).isEqualTo("hook_route");
        assertThat(poppedRouterCnt.get()).isGreaterThan(0);
        assertThat(poppedRouterCnt2.get()).isGreaterThan(0);
    }

    @Test
    void contextHook_otherRoute() {
        assertThat(poppedRouterCnt.get()).isEqualTo(0);

        final WebClient client = WebClient.builder(server.httpUri()).build();
        final AggregatedHttpResponse response = client.get("/").aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(poppedRouterCnt.get()).isEqualTo(0);
    }

    @Test
    void httpMaxResetFramesPerMinute() {
        final ServerConfig config = Server.builder()
                                          .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                          .http2MaxResetFramesPerWindow(99, 2)
                                          .build()
                                          .config();
        assertThat(config.http2MaxResetFramesPerWindow()).isEqualTo(99);
        assertThat(config.http2MaxResetFramesWindowSeconds()).isEqualTo(2);
    }
}
