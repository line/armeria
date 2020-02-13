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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.BouncyCastleKeyFactoryProvider;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.handler.ssl.SslContextBuilder;

class ServerBuilderTest {

    private static ClientFactory clientFactory;

    @RegisterExtension
    static final SelfSignedCertificateExtension selfSignedCertificate = new SelfSignedCertificateExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/test", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator((delegate, ctx, req) -> {
                  ctx.addAdditionalResponseHeader("global_decorator", "true");
                  return delegate.serve(ctx, req);
              })
              .virtualHost("*.example.com")
              .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator((delegate, ctx, req) -> {
                  ctx.addAdditionalResponseHeader("virtualhost_decorator", "true");
                  return delegate.serve(ctx, req);
              });
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
        clientFactory.close();
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
                                .build();
        assertThat(sb.config().defaultVirtualHost()).isNotNull();
        assertThat(sb.config().defaultVirtualHost().accessLogger().getName()).isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example.com").accessLogger().getName())
                .isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example2.com").accessLogger().getName())
                .isEqualTo("com.ex2");

        assertThat(sb.config().findVirtualHost("*.example3.com").accessLogger().getName())
                .isEqualTo("com.ex3");

        assertThat(sb.config().findVirtualHost("*.example4.com").accessLogger().getName())
                .isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example5.com").accessLogger().getName())
                .isEqualTo("com.ex5");
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
        assertThat(sb.config().findVirtualHost("*.example.com").accessLogger().getName())
                .isEqualTo("test.default");
    }

    /**
     * Makes sure that {@link VirtualHost}s can have a proper {@link Logger} used for writing access
     * when a user doesn't specify it.
     */
    @Test
    void defaultAccessLoggerTest() {
        final Server sb = Server.builder()
                                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                .virtualHost("*.example.com")
                                .and()
                                .virtualHost("*.example2.com")
                                .and()
                                .build();
        assertThat(sb.config().findVirtualHost("*.example.com").accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example");
        assertThat(sb.config().findVirtualHost("*.example2.com").accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example2");
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
                                            h -> h.hostnamePattern("foo.com")
                                                  .service("/custom_virtual_host",
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
    @CsvSource({ "/pkcs5.pem", "/pkcs8.pem" })
    void tlsPkcsPrivateKeys(String privateKeyPath) {
        final String resourceRoot =
                '/' + BouncyCastleKeyFactoryProvider.class.getPackage().getName().replace('.', '/') + '/';
        Server.builder()
              .tls(getClass().getResourceAsStream("/cert.pem"),
                   getClass().getResourceAsStream(privateKeyPath))
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .build();
    }

    @ParameterizedTest
    @CsvSource({ "/pkcs5.pem", "/pkcs8.pem" })
    void tlsPkcsPrivateKeysWithCustomizer(String privateKeyPath) {
        Server.builder()
              .tlsSelfSigned()
              .tlsCustomizer(sslCtxBuilder -> {
                  sslCtxBuilder.keyManager(
                          getClass().getResourceAsStream("/cert.pem"),
                          getClass().getResourceAsStream(privateKeyPath));
              })
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .build();
    }
}
