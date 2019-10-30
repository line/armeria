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

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.internal.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ServerBuilderTest {

    private static ClientFactory clientFactory;

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
              })
              .build();
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
     * Makes sure that {@link ServerBuilder#decorator(DecoratingServiceFunction)} works at every service and
     * virtual hosts and {@link VirtualHostBuilder#decorator(DecoratingServiceFunction)} works only at
     * its own services.
     */
    @Test
    void decoratorTest() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));
        final AggregatedHttpResponse res = client.get("/").aggregate().get();
        assertThat(res.headers().get("global_decorator")).isEqualTo("true");
        assertThat(res.headers().contains("virtualhost_decorator")).isEqualTo(false);
        final AggregatedHttpResponse res2 = client.get("/test").aggregate().get();
        assertThat(res2.headers().get("global_decorator")).isEqualTo("true");
        assertThat(res2.headers().contains("virtualhost_decorator")).isEqualTo(false);

        final HttpClient vhostClient = HttpClient.of(clientFactory,
                                                     "http://test.example.com:" + server.httpPort());
        final AggregatedHttpResponse res3 = vhostClient.get("/").aggregate().get();
        assertThat(res3.headers().get("global_decorator")).isEqualTo("true");
        assertThat(res3.headers().get("virtualhost_decorator")).isEqualTo("true");
    }

    @Test
    void serveWithDefaultVirtualHostServiceIfNotExists() {
        final Server server = new ServerBuilder()
                .serviceUnder("/", (ctx, req) -> HttpResponse.of("default"))
                .service("/abc", (ctx, req) -> HttpResponse.of("default_abc"))
                .virtualHost("foo.com")
                .service("/", (ctx, req) -> HttpResponse.of("foo"))
                .service("/abc", (ctx, req) -> HttpResponse.of("foo_abc"))
                .and().build();
        server.start().join();

        final HttpClient client = HttpClient.of(clientFactory, "http://127.0.0.1:" + server.activeLocalPort());
        final HttpClient fooClient = HttpClient.of(clientFactory, "http://foo.com:" + server.activeLocalPort());

        assertThat(client.get("/").aggregate().join().contentUtf8()).isEqualTo("default");
        assertThat(client.get("/abc").aggregate().join().contentUtf8()).isEqualTo("default_abc");

        assertThat(fooClient.get("/").aggregate().join().contentUtf8()).isEqualTo("foo");
        assertThat(fooClient.get("/abc").aggregate().join().contentUtf8()).isEqualTo("foo_abc");

        // should route to a service of default virtual host
        assertThat(fooClient.get("/unknown").aggregate().join().contentUtf8()).isEqualTo("default");
    }

    @Test
    void serviceConfigurationPriority() {
        final Server server = new ServerBuilder()
                .requestTimeoutMillis(100)     // for default virtual host
                .service("/default_virtual_host",
                         (ctx, req) -> HttpResponse.delayed(
                                 HttpResponse.of(HttpStatus.OK), Duration.ofMillis(200))
                )
                .route().get("/service_config")
                .requestTimeoutMillis(200)     // for service
                .build((ctx, req) -> HttpResponse
                        .delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(250)))
                .virtualHost("foo.com")
                .service("/custom_virtual_host", (ctx, req) -> HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.OK), Duration.ofMillis(150)))
                .requestTimeoutMillis(300)    // for custom virtual host
                .and().build();
        server.start().join();

        final HttpClient client = HttpClient.of(clientFactory, "http://127.0.0.1:" + server.activeLocalPort());
        final HttpClient fooClient = HttpClient.of(clientFactory, "http://foo.com:" + server.activeLocalPort());

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
    }
}
