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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;

public class VirtualHostBuilderTest {

    @Test
    public void defaultVirtualHost() {
        final ServerBuilder sb = new ServerBuilder();
        final VirtualHostBuilder virtualHostBuilder = sb.withDefaultVirtualHost();
        assertThat(virtualHostBuilder).isNotNull();
        assertThat(virtualHostBuilder).isEqualTo(sb.withDefaultVirtualHost());

        final Server server = sb.withDefaultVirtualHost()
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and().build();

        assertThat(server).isNotNull();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isNotEqualTo("*");
    }

    @Test
    public void defaultVirtualHostSetDefaultHostname() {
        final ServerBuilder sb = new ServerBuilder();
        sb.defaultHostname("foo");
        final VirtualHostBuilder virtualHostBuilder = sb.withDefaultVirtualHost();
        assertThat(virtualHostBuilder).isNotNull();
        assertThat(virtualHostBuilder).isEqualTo(sb.withDefaultVirtualHost());

        final Server server = sb.withDefaultVirtualHost()
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and().build();

        assertThat(server).isNotNull();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo");
    }

    @Test
    public void defaultVirtualHostWithImplicitStyle() {
        final ServerBuilder sb = new ServerBuilder();
        final Server server = sb.service("/test", (ctx, req) -> HttpResponse.of(OK)).build();
        assertThat(server).isNotNull();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
    }

    @Test
    public void virtualHostWithHostnamePattern() {
        final ServerBuilder sb = new ServerBuilder();
        final VirtualHostBuilder virtualHostBuilder = sb.withVirtualHost("*.foo.com");
        assertThat(virtualHostBuilder).isNotNull();

        final Server server = sb.withDefaultVirtualHost()
                                .service("/test", (ctx, req) -> HttpResponse.of(OK))
                                .and().build();

        assertThat(server).isNotNull();

        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts).isNotNull();
        assertThat(virtualHosts.size()).isEqualTo(2);

        final VirtualHost virtualHost = virtualHosts.get(0);
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*.foo.com");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo.com");

        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertThat(defaultVirtualHost).isNotNull();
        assertThat(defaultVirtualHost).isEqualTo(server.config().defaultVirtualHost());
    }

    @Test
    public void virtualHostWithDefaultHostnameAndHostnamePattern() {
        final ServerBuilder sb = new ServerBuilder();
        final VirtualHostBuilder virtualHostBuilder = sb.withVirtualHost("foo", "*");
        assertThat(virtualHostBuilder).isNotNull();

        virtualHostBuilder.service("/test", (ctx, req) -> HttpResponse.of(OK));
        final Server server = sb.build();
        assertThat(server).isNotNull();

        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts).isNotNull();
        assertThat(virtualHosts.size()).isEqualTo(2);

        final VirtualHost virtualHost = virtualHosts.get(0);
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isEqualTo("foo");

        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertThat(defaultVirtualHost).isNotNull();
        assertThat(defaultVirtualHost).isEqualTo(server.config().defaultVirtualHost());
    }

    @Test
    public void defaultVirtualHostMixedStyle() {
        final ServerBuilder sb = new ServerBuilder();
        sb.service("/test", (ctx, req) -> HttpResponse.of(OK))
          .withDefaultVirtualHost().service("/test2", (ctx, req) -> HttpResponse.of(OK));

        final Server server = sb.build();
        assertThat(server).isNotNull();

        final List<ServiceConfig> serviceConfigs = server.config().defaultVirtualHost().serviceConfigs();
        assertThat(serviceConfigs.size()).isEqualTo(2);
    }

    @Test
    public void virtualHostWithoutPattern() {
        final VirtualHost h = new VirtualHostBuilder("foo.com", "foo.com", new ServerBuilder()).build();
        assertThat(h.hostnamePattern()).isEqualTo("foo.com");
        assertThat(h.defaultHostname()).isEqualTo("foo.com");
    }

    @Test
    public void virtualHostWithPattern() {
        final VirtualHost h = new VirtualHostBuilder("bar.foo.com", "*.foo.com",
                                                     new ServerBuilder())
                .build();
        assertThat(h.hostnamePattern()).isEqualTo("*.foo.com");
        assertThat(h.defaultHostname()).isEqualTo("bar.foo.com");
    }

    @Test
    public void accessLoggerCustomization() {
        final VirtualHost h2 = new VirtualHostBuilder("bar.foo.com", "*.foo.com", new ServerBuilder())
                .accessLogger(host -> LoggerFactory.getLogger("customize.test")).build();
        assertThat(h2.accessLogger().getName()).isEqualTo("customize.test");

        final VirtualHost h = new VirtualHostBuilder("bar.foo.com", "*.foo.com", new ServerBuilder())
                .accessLogger(LoggerFactory.getLogger("com.foo.test")).build();
        assertThat(h.accessLogger().getName()).isEqualTo("com.foo.test");
    }

    @Test(expected = NullPointerException.class)
    public void virtualHostWithNull() {
        final ServerBuilder sb = new ServerBuilder();
        sb.withVirtualHost(null);
    }

    @Test(expected = NullPointerException.class)
    public void virtualHostWithNull2() {
        final ServerBuilder sb = new ServerBuilder();
        sb.withVirtualHost(null,  "foo.com");
    }

    @Test(expected = NullPointerException.class)
    public void virtualHostWithNull3() {
        final ServerBuilder sb = new ServerBuilder();
        sb.withVirtualHost(null,  null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void virtualHostWithMismatch() {
        new VirtualHostBuilder("bar.com", "foo.com", new ServerBuilder()).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void virtualHostWithMismatch2() {
        new VirtualHostBuilder("bar.com", "*.foo.com", new ServerBuilder()).build();
    }
}
