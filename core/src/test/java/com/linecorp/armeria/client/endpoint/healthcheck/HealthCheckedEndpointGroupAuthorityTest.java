/*
 * Copyright 2019 LINE Corporation
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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.RequestHeaders;

class HealthCheckedEndpointGroupAuthorityTest {

    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    private final BlockingQueue<RequestHeaders> logs = new LinkedTransferQueue<>();

    @BeforeEach
    void clearLogs() {
        logs.clear();
    }

    @ParameterizedTest
    @CsvSource({
            // Host name only
            "localhost, localhost",
            "localhost:1, localhost:1",
            "localhost:80, localhost",
            // IPv4 address only
            "127.0.0.1, 127.0.0.1",
            "127.0.0.1:1, 127.0.0.1:1",
            "127.0.0.1:80, 127.0.0.1",
            // IPv6 address only
            "[::1], [::1]",
            "[::1]:1, [::1]:1",
            "[::1]:80, [::1]"
    })
    void hostOnlyOrIpAddrOnly(String endpoint, String expectedAuthority) throws Exception {
        try (HealthCheckedEndpointGroup ignored = build(Endpoint.parse(endpoint))) {
            final RequestHeaders log = logs.take();
            assertThat(log.authority()).isEqualTo(expectedAuthority);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "foo, 127.0.0.1, foo",
            "foo:1, 127.0.0.1, foo:1",
            "foo:80, 127.0.0.1, foo",
            "foo, ::1, foo",
            "foo:1, ::1, foo:1",
            "foo:80, ::1, foo"
    })
    void hostAndIpAddr(String endpoint, String ipAddr, String expectedAuthority) throws Exception {
        try (HealthCheckedEndpointGroup ignored = build(Endpoint.parse(endpoint).withIpAddr(ipAddr))) {
            final RequestHeaders log = logs.take();
            assertThat(log.authority()).isEqualTo(expectedAuthority);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "127.0.0.1, 80, 127.0.0.1",
            "127.0.0.1, 8080, 127.0.0.1:8080",
            "127.0.0.1:80, 80, 127.0.0.1",
            "127.0.0.1:80, 8080, 127.0.0.1:8080",
            "127.0.0.1:8080, 80, 127.0.0.1",
            "127.0.0.1:8080, 8080, 127.0.0.1:8080"
    })
    void alternativePort(String endpoint, int altPort, String expectedAuthority) throws Exception {
        try (HealthCheckedEndpointGroup ignored = build(Endpoint.parse(endpoint),
                                                        builder -> builder.port(altPort))) {
            final RequestHeaders log = logs.take();
            assertThat(log.authority()).isEqualTo(expectedAuthority);
        }
    }

    private HealthCheckedEndpointGroup build(Endpoint endpoint) {
        return build(endpoint, builder -> {});
    }

    private HealthCheckedEndpointGroup build(Endpoint endpoint,
                                             Consumer<HealthCheckedEndpointGroupBuilder> customizer) {

        final HealthCheckedEndpointGroupBuilder builder =
                HealthCheckedEndpointGroup.builder(endpoint, HEALTH_CHECK_PATH);
        builder.withClientOptions(b -> {
            b.decorator(LoggingClient.newDecorator());
            b.decorator((delegate, ctx, req) -> {
                // Record when health check requests were sent.
                logs.add(req.headers());
                return delegate.execute(ctx, req);
            });
            return b;
        });
        customizer.accept(builder);
        return builder.build();
    }
}
