/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.junit.Test;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.http.HttpService;

public class ChainedVirtualHostBuilderTest {
    
    @Test
    public void defaultVirtualHost() {
        final ServerBuilder sb = new ServerBuilder();
        final ChainedVirtualHostBuilder chainedVirtualHostBuilder = sb.withDefaultVirtualHost();
        assertNotNull(chainedVirtualHostBuilder);
        assertThat(chainedVirtualHostBuilder, is(sb.withDefaultVirtualHost()));
        
        final Server server = sb.withDefaultVirtualHost()
                .serviceAt("/test", new TempService())
                .and().build();
        assertNotNull(server);
        
        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertNotNull(virtualHost);
        assertThat(virtualHost.hostnamePattern(), is("*"));
        assertThat(virtualHost.defaultHostname(), is(not("*")));
    }
    
    @Test
    public void defaultVirtualHostWithImplicitStyle() {
        final ServerBuilder sb = new ServerBuilder();
        final Server server = sb.serviceAt("/test", new TempService()).build();
        assertNotNull(server);
        
        final VirtualHost virtualHost = server.config().defaultVirtualHost();
        assertNotNull(virtualHost);
        assertThat(virtualHost.hostnamePattern(), is("*"));
    }
    
    @Test
    public void virtualHostWithHostnamepattern() {
        final ServerBuilder sb = new ServerBuilder();
        final ChainedVirtualHostBuilder chainedVirtualHostBuilder = sb.withVirtualHost("*.foo.com");
        assertNotNull(chainedVirtualHostBuilder);
        
        final Server server = sb.withDefaultVirtualHost()
                .serviceAt("/test", new TempService())
                .and().build();
        assertNotNull(server);
        
        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        
        assertNotNull(virtualHosts);
        assertThat(virtualHosts.size(), is(2));
        
        final VirtualHost virtualHost = virtualHosts.get(0);
        assertNotNull(virtualHost);
        assertThat(virtualHost.hostnamePattern(), is("*.foo.com"));
        assertThat(virtualHost.defaultHostname(), is("foo.com"));
        
        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertNotNull(defaultVirtualHost);
        assertThat(defaultVirtualHost, is(server.config().defaultVirtualHost()));
    }

    @Test
    public void virtualHostWithDefaultHostnameAndHostnamepattern() {
        final ServerBuilder sb = new ServerBuilder();
        final ChainedVirtualHostBuilder chainedVirtualHostBuilder = sb.withVirtualHost("foo", "*");
        assertNotNull(chainedVirtualHostBuilder);
        
        chainedVirtualHostBuilder.serviceAt("/test", new TempService());
        final Server server = sb.build();
        assertNotNull(server);
        
        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        
        assertNotNull(virtualHosts);
        assertThat(virtualHosts.size(), is(2));
        
        final VirtualHost virtualHost = virtualHosts.get(0);
        assertNotNull(virtualHost);
        assertThat(virtualHost.hostnamePattern(), is("*"));
        assertThat(virtualHost.defaultHostname(), is("foo"));
        
        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertNotNull(defaultVirtualHost);
        assertThat(defaultVirtualHost, is(server.config().defaultVirtualHost()));
    }

    @Test
    public void virtualHostWithCreateStyle() {
        final VirtualHost h = new VirtualHostBuilder("foo", "*").build();
        assertThat(h.hostnamePattern(), is("*"));
        assertThat(h.defaultHostname(), is("foo"));
        
        final ServerBuilder sb = new ServerBuilder();
        sb.virtualHost(h);
        final Server server = sb.serviceAt("/test", new TempService()).build();
        assertNotNull(server);
        
        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertNotNull(virtualHosts);
        assertThat(virtualHosts.size(), is(2));
        
        final VirtualHost virtualHost = virtualHosts.get(0);
        assertNotNull(virtualHost);
        assertThat(virtualHost.hostnamePattern(), is("*"));
        assertThat(virtualHost.defaultHostname(), is("foo"));
        
        final VirtualHost defaultVirtualHost = virtualHosts.get(1);
        assertNotNull(defaultVirtualHost);
        assertThat(defaultVirtualHost, is(server.config().defaultVirtualHost()));
    }
    
    @Test
    public void defaultVirtalHostMixedStyle() {
        final ServerBuilder sb = new ServerBuilder();
        sb.serviceAt("/test", new TempService())
        .withDefaultVirtualHost()
            .serviceAt("/test2", new TempService());
        
        final Server server = sb.build();
        assertNotNull(server);
        
        final List<ServiceConfig> serviceConfigs = server.config()
                .defaultVirtualHost()
                .serviceConfigs();
        assertThat(serviceConfigs.size(), is(2));
    }

    @Test
    public void virtualHostMixedStyleTest() {
        final VirtualHost h = new VirtualHostBuilder("bar.foo.com")
                .serviceAt("/test", new TempService())
                .build();
        
        final ServerBuilder sb = new ServerBuilder();
        sb.withVirtualHost("*.some.com")
            .serviceAt("/test2", new TempService())
        .and().virtualHost(h);
        
        final Server server = sb.build();
        assertNotNull(server);
        
        final List<VirtualHost> virtualHosts = server.config().virtualHosts();
        assertThat(virtualHosts.size(), is(3));
        
        final VirtualHost virtualHost = virtualHosts.get(0);
        assertNotNull(virtualHost);
        assertThat(virtualHost.hostnamePattern(), is("bar.foo.com"));
        assertThat(virtualHost.defaultHostname(), is("bar.foo.com"));
        
        final VirtualHost virtualHost2 = virtualHosts.get(1);
        assertNotNull(virtualHost2);
        assertThat(virtualHost2.hostnamePattern(), is("*.some.com"));
        assertThat(virtualHost2.defaultHostname(), is("some.com"));
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
