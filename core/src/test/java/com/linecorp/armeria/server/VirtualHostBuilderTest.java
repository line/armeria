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

import static com.linecorp.armeria.common.HttpStatus.OK;
import static com.linecorp.armeria.server.RoutingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;

class VirtualHostBuilderTest {

    private static final VirtualHostBuilder template = Server.builder().virtualHostTemplate;

    @Test
    void defaultVirtualHost() {
        final ServerBuilder sb = Server.builder();
        final Server server = sb.defaultVirtualHost()
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and().build();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isNotEqualTo("*");
    }

    @Test
    void withDefaultVirtualHost() {
        final ServerBuilder sb = Server.builder();

        final Server server = sb.withDefaultVirtualHost(builder -> {
            builder.defaultHostname("foo")
                   .service("/test", (ctx, req) -> HttpResponse.of(OK));
        }).build();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo");
    }

    @Test
    void defaultVirtualHostSetDefaultHostname() {
        final ServerBuilder sb = Server.builder();
        sb.defaultHostname("foo");
        final Server server = sb.defaultVirtualHost()
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and().build();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo");
    }

    @Test
    void defaultVirtualHostWithImplicitStyle() {
        final ServerBuilder sb = Server.builder();
        final Server server = sb.service("/test", (ctx, req) -> HttpResponse.of(OK)).build();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
    }

    @Test
    void virtualHostWithHostnamePattern() {
        final ServerBuilder sb = Server.builder();
        final Server server = sb.virtualHost("*.foo.com")
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and()
                                .build();

        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts.size()).isEqualTo(2);

        final VirtualHost virtualHost = virtualHosts.get(0);
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*.foo.com");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo.com");

        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertThat(defaultVirtualHost).isEqualTo(server.config().defaultVirtualHost());
    }

    @ParameterizedTest
    @CsvSource({ "foo, foo", "bar, *.bar", "a.baz, *.baz" })
    void virtualHostWithDefaultHostnameAndHostnamePattern(String defaultHostname, String hostnamePattern) {
        final ServerBuilder sb = Server.builder();
        final Server server = sb.virtualHost(defaultHostname, hostnamePattern)
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and()
                                .build();

        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts.size()).isEqualTo(2);

        final VirtualHost virtualHost = virtualHosts.get(0);
        assertThat(virtualHost.hostnamePattern()).isEqualTo(hostnamePattern);
        assertThat(virtualHost.defaultHostname()).isEqualTo(defaultHostname);

        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertThat(defaultVirtualHost).isEqualTo(server.config().defaultVirtualHost());
    }

    @Test
    void withVirtualHost() {
        final ServerBuilder sb = Server.builder();
        final Server server = sb.withVirtualHost(builder -> {
            builder.defaultHostname("foo")
                   .service("/test", (ctx, req) -> HttpResponse.of(OK));
        }).build();

        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts.size()).isEqualTo(2);

        final VirtualHost virtualHost = virtualHosts.get(0);
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*.foo");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo");
    }

    @Test
    void defaultVirtualHostMixedStyle() {
        final ServerBuilder sb = Server.builder();
        sb.service("/test", (ctx, req) -> HttpResponse.of(OK))
          .defaultVirtualHost().service("/test2", (ctx, req) -> HttpResponse.of(OK));

        final Server server = sb.build();

        final List<ServiceConfig> serviceConfigs = server.config().defaultVirtualHost().serviceConfigs();
        assertThat(serviceConfigs.size()).isEqualTo(2);
    }

    @Test
    void virtualHostWithoutPattern() {
        final VirtualHost h = new VirtualHostBuilder(Server.builder(), false)
                .defaultHostname("foo.com")
                .hostnamePattern("foo.com")
                .build(template);
        assertThat(h.hostnamePattern()).isEqualTo("foo.com");
        assertThat(h.defaultHostname()).isEqualTo("foo.com");
    }

    @Test
    void virtualHostWithPattern() {
        final VirtualHost h = new VirtualHostBuilder(Server.builder(), false)
                .defaultHostname("bar.foo.com")
                .hostnamePattern("*.foo.com")
                .build(template);
        assertThat(h.hostnamePattern()).isEqualTo("*.foo.com");
        assertThat(h.defaultHostname()).isEqualTo("bar.foo.com");
    }

    @Test
    void accessLoggerCustomization() {
        final VirtualHost h1 = new VirtualHostBuilder(Server.builder(), false)
                .defaultHostname("bar.foo.com")
                .hostnamePattern("*.foo.com")
                .accessLogger(host -> LoggerFactory.getLogger("customize.test"))
                .build(template);
        assertThat(h1.accessLogger().getName()).isEqualTo("customize.test");

        final VirtualHost h2 = new VirtualHostBuilder(Server.builder(), false)
                .defaultHostname("bar.foo.com")
                .hostnamePattern("*.foo.com")
                .accessLogger(LoggerFactory.getLogger("com.foo.test"))
                .build(template);
        assertThat(h2.accessLogger().getName()).isEqualTo("com.foo.test");
    }

    @Test
    void hostnamePatternCannotBeSetForDefaultBuilder() {
        final ServerBuilder sb = Server.builder();
        assertThatThrownBy(() -> sb.defaultVirtualHost().hostnamePattern("CannotSet"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hostnamePatternCannotBeSetForDefaultBuilder2() {
        final ServerBuilder sb = Server.builder();
        assertThatThrownBy(() -> sb.withDefaultVirtualHost(builder -> builder.hostnamePattern("CannotSet")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void virtualHostWithNull2() {
        final ServerBuilder sb = Server.builder();
        assertThatThrownBy(() -> sb.virtualHost(null, "foo.com")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void virtualHostWithNull3() {
        final ServerBuilder sb = Server.builder();
        assertThatThrownBy(() -> sb.virtualHost(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void virtualHostWithMismatch() {
        assertThatThrownBy(() -> {
            new VirtualHostBuilder(Server.builder(), false)
                    .defaultHostname("bar.com")
                    .hostnamePattern("foo.com")
                    .build(template);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void virtualHostWithMismatch2() {
        assertThatThrownBy(() -> {
            new VirtualHostBuilder(Server.builder(), false)
                    .defaultHostname("bar.com")
                    .hostnamePattern("*.foo.com")
                    .build(template);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void precedenceOfDuplicateRoute() {
        final Route routeA = Route.builder().path("/").build();
        final Route routeB = Route.builder().path("/").build();
        final VirtualHost virtualHost = new VirtualHostBuilder(Server.builder(), true)
                .service(routeA, (ctx, req) -> HttpResponse.of(OK))
                .service(routeB, (ctx, req) -> HttpResponse.of(OK))
                .build(template);
        assertThat(virtualHost.serviceConfigs().size()).isEqualTo(2);
        final RoutingContext routingContext = new DefaultRoutingContext(virtualHost(), "example.com",
                                                                        RequestHeaders.of(HttpMethod.GET, "/"),
                                                                        "/", null, false);
        final Routed<ServiceConfig> serviceConfig = virtualHost.findServiceConfig(routingContext);
        final Route route = serviceConfig.route();
        assertThat(route).isSameAs(routeA);
    }
}
