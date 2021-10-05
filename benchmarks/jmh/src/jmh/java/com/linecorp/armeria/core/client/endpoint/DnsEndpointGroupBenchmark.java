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

package com.linecorp.armeria.core.client.endpoint;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

/**
 * Microbenchmarks of a {@link com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup}.
 */
@State(Scope.Thread)
public class DnsEndpointGroupBenchmark {

    private static final AggregatedHttpResponse OK = AggregatedHttpResponse.of(HttpStatus.OK);

    private Server server;
    private HealthCheckedEndpointGroup endpointGroup;

    @Setup(Level.Trial)
    public void startServer() {
        server = Server.builder()
                       .service("/health", (ctx, req) -> OK.toHttpResponse())
                       .build();
        server.start().join();
    }

    @TearDown(Level.Trial)
    public void stopServer() {
        server.stop();
    }

    @Setup(Level.Invocation)
    public void setUp() {
        endpointGroup = HealthCheckedEndpointGroup.of(
                DnsAddressEndpointGroup.of("localhost",
                                           server.activeLocalPort()), "/health");
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
        endpointGroup.close();
    }

    @Benchmark
    public Object resolveLocalhost() throws Exception {
        return endpointGroup.whenReady().get();
    }
}
