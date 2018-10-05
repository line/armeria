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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Collection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

@RunWith(Parameterized.class)
public class HttpHealthCheckedEndpointGroupTest {

    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    @Parameters(name = "{index}: protocol={0}")
    public static Collection<SessionProtocol> protocols() {
        return ImmutableList.of(HTTP, HTTPS);
    }

    private static class HealthCheckServerRule extends ServerRule {

        protected HealthCheckServerRule() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service(HEALTH_CHECK_PATH, new HttpHealthCheckService());
        }
    }

    private final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();

    @Rule
    public final ServerRule serverOne = new HealthCheckServerRule();

    @Rule
    public final ServerRule serverTwo = new HealthCheckServerRule();

    private final ClientFactory clientFactory = new ClientFactoryBuilder()
            .sslContextCustomizer(s -> s.trustManager(InsecureTrustManagerFactory.INSTANCE))
            .build();

    private final SessionProtocol protocol;

    public HttpHealthCheckedEndpointGroupTest(SessionProtocol protocol) {
        this.protocol = protocol;
    }

    @Test
    public void endpoints() throws Exception {
        serverOne.start();
        serverTwo.start();

        final int portOne = serverOne.port(protocol);
        final int portTwo = serverTwo.port(protocol);
        final HealthCheckedEndpointGroup endpointGroup = new HttpHealthCheckedEndpointGroupBuilder(
                new StaticEndpointGroup(Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portTwo)),
                HEALTH_CHECK_PATH)
                .protocol(protocol)
                .clientFactory(clientFactory)
                .build();

        endpointGroup.newMeterBinder("foo").bindTo(registry);

        await().untilAsserted(() -> {
            assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(
                    Endpoint.of("127.0.0.1", portOne),
                    Endpoint.of("127.0.0.1", portTwo));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpointGroup.count#value{name=foo,state=healthy}", 2.0)
                    .containsEntry("armeria.client.endpointGroup.count#value{name=foo,state=unhealthy}", 0.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=foo}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portTwo + ",ip=127.0.0.1,name=foo}", 1.0);
        });

        serverTwo.stop().get();
        await().untilAsserted(() -> {
            assertThat(endpointGroup.endpoints()).containsExactly(
                    Endpoint.of("127.0.0.1", portOne));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpointGroup.count#value{name=foo,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.count#value{name=foo,state=unhealthy}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=foo}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portTwo + ",ip=127.0.0.1,name=foo}", 0.0);
        });
    }

    @Test
    public void endpoints_containsUnhealthyServer() throws Exception {
        serverOne.start();

        final int portOne = serverOne.port(protocol);
        final int portTwo = 65535;
        final HealthCheckedEndpointGroup endpointGroup = new HttpHealthCheckedEndpointGroupBuilder(
                new StaticEndpointGroup(Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portTwo)),
                HEALTH_CHECK_PATH)
                .protocol(protocol)
                .clientFactory(clientFactory)
                .build();

        endpointGroup.newMeterBinder("bar").bindTo(registry);

        await().untilAsserted(() -> {
            assertThat(endpointGroup.endpoints())
                    .containsOnly(Endpoint.of("127.0.0.1", portOne));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpointGroup.count#value{name=bar,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.count#value{name=bar,state=unhealthy}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=bar}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portTwo + ",ip=127.0.0.1,name=bar}", 0.0);
        });
    }

    @Test
    public void endpoints_duplicateEntries() throws Exception {
        serverOne.start();

        final int portOne = serverOne.port(protocol);
        final HealthCheckedEndpointGroup endpointGroup = new HttpHealthCheckedEndpointGroupBuilder(
                new StaticEndpointGroup(Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portOne)),
                HEALTH_CHECK_PATH)
                .protocol(protocol)
                .clientFactory(clientFactory)
                .build();

        endpointGroup.newMeterBinder("baz").bindTo(registry);

        await().untilAsserted(() -> {
            assertThat(endpointGroup.endpoints())
                    .containsOnly(Endpoint.of("127.0.0.1", portOne));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpointGroup.count#value{name=baz,state=healthy}", 3.0)
                    .containsEntry("armeria.client.endpointGroup.count#value{name=baz,state=unhealthy}", 0.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=baz}", 1.0);
        });
        serverOne.stop();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
    }

    /**
     * When an endpoint has an IP address already, the health checker must send a health check request using
     * an IP address, because otherwise the health checker can send the health check request to a wrong host
     * if there are more than one IP addresses assigned to the host name.
     */
    @Test
    public void endpoints_customAuthority() throws Exception {
        serverOne.start();

        // This test case will fail if the health check does not use an IP address
        // because the host name 'foo' does not really exist.
        final int port = serverOne.port(protocol);
        final HealthCheckedEndpointGroup endpointGroup = new HttpHealthCheckedEndpointGroupBuilder(
                new StaticEndpointGroup(Endpoint.of("foo", port).withIpAddr("127.0.0.1")),
                HEALTH_CHECK_PATH)
                .protocol(protocol)
                .clientFactory(clientFactory)
                .build();

        endpointGroup.newMeterBinder("qux").bindTo(registry);

        await().untilAsserted(() -> {
            assertThat(endpointGroup.endpoints())
                    .containsOnly(Endpoint.of("foo", port).withIpAddr("127.0.0.1"));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpointGroup.count#value{name=qux,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpointGroup.count#value{name=qux,state=unhealthy}", 0.0)
                    .containsEntry("armeria.client.endpointGroup.healthy#value" +
                                   "{authority=foo:" + port + ",ip=127.0.0.1,name=qux}", 1.0);
        });
    }
}
