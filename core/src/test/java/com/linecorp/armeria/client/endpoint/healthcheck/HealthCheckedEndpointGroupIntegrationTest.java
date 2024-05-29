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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;

class HealthCheckedEndpointGroupIntegrationTest {

    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    private static class HealthCheckServerExtension extends ServerExtension {

        HealthCheckServerExtension() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service(HEALTH_CHECK_PATH, HealthCheckService.builder().longPolling(0).build());
        }
    }

    private final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();

    @RegisterExtension
    static final ServerExtension serverOne = new HealthCheckServerExtension();

    @RegisterExtension
    static final ServerExtension serverTwo = new HealthCheckServerExtension();

    @ParameterizedTest
    @CsvSource({ "HTTP, false", "HTTP, true", "HTTPS, false", "HTTPS, true" })
    void endpoints(SessionProtocol protocol, boolean useGet) throws Exception {
        serverOne.start();
        serverTwo.start();

        final int portOne = serverOne.port(protocol);
        final int portTwo = serverTwo.port(protocol);
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        EndpointGroup.of(Endpoint.of("127.0.0.1", portOne),
                                         Endpoint.of("127.0.0.1", portTwo)),
                        HEALTH_CHECK_PATH).useGet(useGet),
                protocol)) {

            endpointGroup.whenReady().join();
            endpointGroup.newMeterBinder("foo").bindTo(registry);

            assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(
                    Endpoint.of("127.0.0.1", portOne),
                    Endpoint.of("127.0.0.1", portTwo));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpoint.group.count#value{name=foo,state=healthy}", 2.0)
                    .containsEntry("armeria.client.endpoint.group.count#value{name=foo,state=unhealthy}",
                                   0.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=foo}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=127.0.0.1:" + portTwo + ",ip=127.0.0.1,name=foo}", 1.0);

            serverTwo.stop().get();
            await().untilAsserted(() -> {
                assertThat(endpointGroup.endpoints()).containsExactly(
                        Endpoint.of("127.0.0.1", portOne));

                assertThat(MoreMeters.measureAll(registry))
                        .containsEntry("armeria.client.endpoint.group.count#value{name=foo,state=healthy}", 1.0)
                        .containsEntry("armeria.client.endpoint.group.count#value{name=foo,state=unhealthy}",
                                       1.0)
                        .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                       "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=foo}", 1.0)
                        .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                       "{authority=127.0.0.1:" + portTwo + ",ip=127.0.0.1,name=foo}", 0.0);
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "HTTP", "HTTPS" })
    void endpoints_withIpAndNoIp(SessionProtocol protocol) throws Exception {
        serverOne.start();
        serverTwo.start();

        final int portOne = serverOne.port(protocol);
        final int portTwo = serverTwo.port(protocol);

        try (HealthCheckedEndpointGroup groupFoo = build(
                HealthCheckedEndpointGroup.builder(Endpoint.of("127.0.0.1", portOne),
                                                   HEALTH_CHECK_PATH), protocol);
             HealthCheckedEndpointGroup groupBar = build(
                     HealthCheckedEndpointGroup.builder(Endpoint.of("localhost", portTwo),
                                                        HEALTH_CHECK_PATH), protocol)) {
            groupFoo.whenReady().join();
            groupBar.whenReady().join();

            groupFoo.newMeterBinder("foo").bindTo(registry);
            groupBar.newMeterBinder("bar").bindTo(registry);

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpoint.group.count#value{name=foo,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.count#value{name=bar,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=foo}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=localhost:" + portTwo + ",ip=,name=bar}", 1.0);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "HTTP", "HTTPS" })
    void endpoints_customPort(SessionProtocol protocol) throws Exception {
        serverOne.start();
        final int portOne = serverOne.port(protocol);

        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(Endpoint.of("127.0.0.1", 1),
                                                   HEALTH_CHECK_PATH).port(portOne),
                protocol)) {
            endpointGroup.whenReady().join();
            assertThat(endpointGroup.endpoints()).containsOnly(Endpoint.of("127.0.0.1", 1));
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "HTTP", "HTTPS" })
    void endpoints_containsUnhealthyServer(SessionProtocol protocol) throws Exception {
        serverOne.start();

        final int portOne = serverOne.port(protocol);
        final int portTwo = 1;
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        EndpointGroup.of(Endpoint.of("127.0.0.1", portOne),
                                         Endpoint.of("127.0.0.1", portTwo)),
                        HEALTH_CHECK_PATH),
                protocol)) {

            endpointGroup.whenReady().join();
            endpointGroup.newMeterBinder("bar").bindTo(registry);

            assertThat(endpointGroup.endpoints())
                    .containsOnly(Endpoint.of("127.0.0.1", portOne));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpoint.group.count#value{name=bar,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.count#value{name=bar,state=unhealthy}",
                                   1.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=bar}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=127.0.0.1:" + portTwo + ",ip=127.0.0.1,name=bar}", 0.0);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "HTTP", "HTTPS" })
    void endpoints_duplicateEntries(SessionProtocol protocol) throws Exception {
        serverOne.start();

        final int portOne = serverOne.port(protocol);
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        EndpointGroup.of(Endpoint.of("127.0.0.1", portOne),
                                         Endpoint.of("127.0.0.1", portOne),
                                         Endpoint.of("127.0.0.1", portOne)),
                        HEALTH_CHECK_PATH),
                protocol)) {

            endpointGroup.whenReady().join();
            endpointGroup.newMeterBinder("baz").bindTo(registry);

            assertThat(endpointGroup.endpoints())
                    .hasSize(3)
                    .containsOnly(Endpoint.of("127.0.0.1", portOne));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpoint.group.count#value{name=baz,state=healthy}", 3.0)
                    .containsEntry("armeria.client.endpoint.group.count#value{name=baz,state=unhealthy}",
                                   0.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=127.0.0.1:" + portOne + ",ip=127.0.0.1,name=baz}", 1.0);
            serverOne.stop().join();
            await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
        }
    }

    /**
     * When an endpoint has an IP address already, the health checker must send a health check request using
     * an IP address, because otherwise the health checker can send the health check request to a wrong host
     * if there are more than one IP addresses assigned to the host name.
     */
    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "HTTP", "HTTPS" })
    void endpoints_customAuthority(SessionProtocol protocol) throws Exception {
        serverOne.start();

        // This test case will fail if the health check does not use an IP address
        // because the host name 'foo' does not really exist.
        final int port = serverOne.port(protocol);
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        Endpoint.of("foo", port).withIpAddr("127.0.0.1"),
                        HEALTH_CHECK_PATH),
                protocol)) {

            endpointGroup.whenReady().join();
            endpointGroup.newMeterBinder("qux").bindTo(registry);

            assertThat(endpointGroup.endpoints())
                    .containsOnly(Endpoint.of("foo", port).withIpAddr("127.0.0.1"));

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.endpoint.group.count#value{name=qux,state=healthy}", 1.0)
                    .containsEntry("armeria.client.endpoint.group.count#value{name=qux,state=unhealthy}",
                                   0.0)
                    .containsEntry("armeria.client.endpoint.group.healthy#value" +
                                   "{authority=foo:" + port + ",ip=127.0.0.1,name=qux}", 1.0);
        }
    }

    // Make sure we don't start health checking before a delegate has a chance to resolve endpoints, otherwise
    // we're guaranteed to always wait at least the retry interval (3s) before being ready, which at least can
    // slow down tests if not production server startup when waiting on many clients to resolve initial
    // endpoints.
    @Test
    void initialHealthCheckCanHaveEndpoints() throws Exception {
        serverOne.start();

        // even localhost usually takes long enough to resolve that this test would never work if the initial
        // health check didn't wait for localhost's DNS resolution.
        final int port = serverOne.httpPort();
        try (HealthCheckedEndpointGroup endpointGroup =
                     HealthCheckedEndpointGroup.builder(DnsAddressEndpointGroup.of("localhost", port),
                                                        HEALTH_CHECK_PATH)
                                               .retryInterval(Duration.ofHours(1))
                                               .withClientOptions(b -> {
                                                   return b.decorator(LoggingClient.newDecorator());
                                               })
                                               .build()) {

            assertThat(endpointGroup.whenReady().get(10, TimeUnit.SECONDS)).hasSize(1);
        }
    }

    private static HealthCheckedEndpointGroup build(HealthCheckedEndpointGroupBuilder builder,
                                                    SessionProtocol protocol) {
        return builder.protocol(protocol)
                      .clientOptions(ClientOptions.builder()
                                                  .factory(ClientFactory.insecure())
                                                  .decorator(LoggingClient.newDecorator())
                                                  .build())
                      .build();
    }
}
