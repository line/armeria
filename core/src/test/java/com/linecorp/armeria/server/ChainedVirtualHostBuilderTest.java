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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

public class ChainedVirtualHostBuilderTest {

    @Test
    public void defaultVirtualHost() {
        final ServerBuilder sb = new ServerBuilder();
        final ChainedVirtualHostBuilder chainedVirtualHostBuilder = sb.withDefaultVirtualHost();
        assertThat(chainedVirtualHostBuilder).isNotNull();
        assertThat(chainedVirtualHostBuilder).isEqualTo(sb.withDefaultVirtualHost());

        final Server server = sb.withDefaultVirtualHost()
                                .service("/test", new TempService())
                                .and().build();

        assertThat(server).isNotNull();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
        assertThat(virtualHost.defaultHostname()).isNotEqualTo("*");
    }

    @Test
    public void defaultVirtualHostWithImplicitStyle() {
        final ServerBuilder sb = new ServerBuilder();
        final Server server = sb.service("/test", new TempService()).build();
        assertThat(server).isNotNull();

        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("*");
    }

    @Test
    public void virtualHostWithHostnamepattern() {
        final ServerBuilder sb = new ServerBuilder();
        final ChainedVirtualHostBuilder chainedVirtualHostBuilder = sb.withVirtualHost("*.foo.com");
        assertThat(chainedVirtualHostBuilder).isNotNull();

        final Server server = sb.withDefaultVirtualHost()
                                .service("/test", new TempService())
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
    public void virtualHostWithDefaultHostnameAndHostnamepattern() {
        final ServerBuilder sb = new ServerBuilder();
        final ChainedVirtualHostBuilder chainedVirtualHostBuilder = sb.withVirtualHost("foo", "*");
        assertThat(chainedVirtualHostBuilder).isNotNull();

        chainedVirtualHostBuilder.service("/test", new TempService());
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
    public void virtualHostWithCreateStyle() {
        final VirtualHost h = new VirtualHostBuilder("foo", "*").build();
        assertThat(h.hostnamePattern()).isEqualTo("*");
        assertThat(h.defaultHostname()).isEqualTo("foo");

        final ServerBuilder sb = new ServerBuilder();
        sb.virtualHost(h);
        final Server server = sb.service("/test", new TempService()).build();
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
    public void defaultVirtalHostMixedStyle() {
        final ServerBuilder sb = new ServerBuilder();
        sb.service("/test", new TempService())
          .withDefaultVirtualHost().service("/test2", new TempService());

        final Server server = sb.build();
        assertThat(server).isNotNull();

        final List<ServiceConfig> serviceConfigs = server.config().defaultVirtualHost().serviceConfigs();
        assertThat(serviceConfigs.size()).isEqualTo(2);
    }

    @Test
    public void virtualHostMixedStyle() {
        final VirtualHost h =
                new VirtualHostBuilder("bar.foo.com").service("/test", new TempService()).build();

        final ServerBuilder sb = new ServerBuilder();
        sb.withVirtualHost("*.some.com")
          .service("/test2", new TempService())
          .and().virtualHost(h);

        final Server server = sb.build();
        assertThat(server).isNotNull();

        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts.size()).isEqualTo(3);

        final VirtualHost virtualHost = virtualHosts.get(0);
        assertThat(virtualHost).isNotNull();
        assertThat(virtualHost.hostnamePattern()).isEqualTo("bar.foo.com");
        assertThat(virtualHost.defaultHostname()).isEqualTo("bar.foo.com");

        final VirtualHost virtualHost2 = virtualHosts.get(1);
        assertThat(virtualHost2).isNotNull();
        assertThat(virtualHost2.hostnamePattern()).isEqualTo("*.some.com");
        assertThat(virtualHost2.defaultHostname()).isEqualTo("some.com");
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

    private static class TempService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return null;
        }
    }
}
