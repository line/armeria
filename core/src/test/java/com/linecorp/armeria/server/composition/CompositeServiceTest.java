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

package com.linecorp.armeria.server.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.internal.common.metric.MicrometerUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;

class CompositeServiceTest {

    private static final TestService serviceA = new TestService("A");
    private static final TestService serviceB = new TestService("B");
    private static final TestService serviceC = new TestService("C");
    private static final TestService otherService = new TestService("X");

    private static final TestCompositeService composite = new TestCompositeService();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.meterRegistry(PrometheusMeterRegistries.newRegistry());
            sb.serviceUnder("/qux/", composite);

            // Should not hit the following services
            sb.serviceUnder("/foo/", otherService);
            sb.serviceUnder("/bar/", otherService);
            sb.service(Route.builder().glob("/*").build(), otherService);
        }
    };

    @AfterAll
    static void checkMetrics() {
        final MeterRegistry registry = server.server().meterRegistry();
        assertThat(MicrometerUtil.register(registry,
                                           new MeterIdPrefix("armeria.server.router.composite.service.cache",
                                                             "hostname.pattern", "*",
                                                             "route", "prefix:/qux/"),
                                           Object.class, (r, i) -> null)).isNotNull();
    }

    @Test
    void testRoute() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/qux/foo/X"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("A:/qux/foo/X:/X:/X");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/qux/bar/Y"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("B:/qux/bar/Y:/Y:/Y");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/qux/Z"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("C:/qux/Z:/Z:/Z");
            }

            // Make sure encoded path is handled correctly.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/qux/%C2%A2"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("C:/qux/%C2%A2:/%C2%A2:/¢");
            }
        }
    }

    @Test
    void testNonExistentRoute() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/qux/Z/T"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 404 Not Found");
            }
        }
    }

    @Test
    void testServiceGetters() throws Exception {
        assertThat((Object) composite.serviceAt(0)).isSameAs(serviceA);
        assertThat((Object) composite.serviceAt(1)).isSameAs(serviceB);
        assertThat((Object) composite.serviceAt(2)).isSameAs(serviceC);

        try {
            composite.serviceAt(-1);
            fail();
        } catch (IndexOutOfBoundsException e) {
            // Expected
        }
        try {
            composite.serviceAt(3);
            fail();
        } catch (IndexOutOfBoundsException e) {
            // Expected
        }
    }

    @Test
    void failWhenThePathIsNotPrefix() {
        assertThatThrownBy(() -> Server.builder()
                                       .service("/exact", new TestCompositeService())
                                       .build()).isInstanceOf(IllegalStateException.class);
    }

    private static final class TestCompositeService
            extends AbstractCompositeService<HttpService, HttpRequest, HttpResponse> implements HttpService {

        TestCompositeService() {
            super(CompositeServiceEntry.ofPrefix("/foo/", serviceA),
                  CompositeServiceEntry.ofPrefix("/bar/", serviceB),
                  CompositeServiceEntry.ofGlob("/*", serviceC)); // Matches /x but doesn't match /x/y
        }
    }

    private static final class TestService extends AbstractHttpService {

        private final String name;

        TestService(String name) {
            this.name = name;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                   "%s:%s:%s:%s", name, ctx.path(), ctx.mappedPath(), ctx.decodedMappedPath());
        }
    }
}
