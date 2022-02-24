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

package com.linecorp.armeria.server;

import java.util.List;

import org.openjdk.jmh.annotations.Benchmark;
import org.slf4j.helpers.NOPLogger;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.logging.AccessLogWriter;

public class RoutersBenchmark {

    private static final HttpService SERVICE =
            (ctx, req) -> HttpResponse.of(HttpStatus.OK);

    private static final List<ServiceConfig> SERVICES;
    private static final ServiceConfig FALLBACK_SERVICE;
    private static final VirtualHost HOST;
    private static final Router<ServiceConfig> ROUTER;

    private static final RequestHeaders METHOD1_HEADERS =
            RequestHeaders.of(HttpMethod.POST, "/grpc.package.Service/Method1");

    static {
        final String defaultLogName = null;
        final String defaultServiceName = null;
        final ServiceNaming defaultServiceNaming = ServiceNaming.of("Service");
        final Route route1 = Route.builder().exact("/grpc.package.Service/Method1").build();
        final Route route2 = Route.builder().exact("/grpc.package.Service/Method2").build();
        SERVICES = ImmutableList.of(
                new ServiceConfig(route1, route1,
                                  SERVICE, defaultLogName, defaultServiceName, defaultServiceNaming, 0, 0,
                                  false, AccessLogWriter.disabled(), false, CommonPools.blockingTaskExecutor(),
                                  true),
                new ServiceConfig(route2, route2,
                                  SERVICE, defaultLogName, defaultServiceName, defaultServiceNaming, 0, 0,
                                  false, AccessLogWriter.disabled(), false, CommonPools.blockingTaskExecutor(),
                                  true)
        );
        FALLBACK_SERVICE = new ServiceConfig(Route.ofCatchAll(), Route.ofCatchAll(), SERVICE,
                                             defaultLogName, defaultServiceName,
                                             defaultServiceNaming, 0, 0, false, AccessLogWriter.disabled(),
                                             false, CommonPools.blockingTaskExecutor(), true);
        HOST = new VirtualHost(
                "localhost", "localhost", 0, null, SERVICES, FALLBACK_SERVICE, RejectedRouteHandler.DISABLED,
                unused -> NOPLogger.NOP_LOGGER, defaultServiceNaming, 0, 0, false,
                AccessLogWriter.disabled(), false, CommonPools.blockingTaskExecutor(), true);
        ROUTER = Routers.ofVirtualHost(HOST, SERVICES, RejectedRouteHandler.DISABLED);
    }

    @Benchmark
    public Routed<ServiceConfig> exactMatch() {
        final RoutingContext ctx = DefaultRoutingContext.of(HOST, "localhost", METHOD1_HEADERS.path(),
                                                            null, METHOD1_HEADERS, false);
        final Routed<ServiceConfig> routed = ROUTER.find(ctx);
        if (routed.value() != SERVICES.get(0)) {
            throw new IllegalStateException("Routing error");
        }
        return routed;
    }

    @Benchmark
    public Routed<ServiceConfig> exactMatch_wrapped() {
        final RoutingContext ctx = new RoutingContextWrapper(
                DefaultRoutingContext.of(HOST, "localhost", METHOD1_HEADERS.path(),
                                         null, METHOD1_HEADERS, false));
        final Routed<ServiceConfig> routed = ROUTER.find(ctx);
        if (routed.value() != SERVICES.get(0)) {
            throw new IllegalStateException("Routing error");
        }
        return routed;
    }
}
