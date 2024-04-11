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

import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;

import java.nio.file.Path;
import java.util.List;

import org.openjdk.jmh.annotations.Benchmark;
import org.slf4j.helpers.NOPLogger;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SuccessFunction;
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

    private static final RequestTarget METHOD1_REQ_TARGET = RequestTarget.forServer(METHOD1_HEADERS.path());

    static {
        final String defaultLogName = "log";
        final String defaultServiceName = null;
        final ServiceNaming defaultServiceNaming = ServiceNaming.of("Service");
        final Route route1 = Route.builder().exact("/grpc.package.Service/Method1").build();
        final Route route2 = Route.builder().exact("/grpc.package.Service/Method2").build();
        final Path multipartUploadsLocation = Flags.defaultMultipartUploadsLocation();
        final ServiceErrorHandler serviceErrorHandler = ServerErrorHandler.ofDefault().asServiceErrorHandler();
        SERVICES = ImmutableList.of(
                new ServiceConfig(route1, route1,
                                  SERVICE, defaultLogName, defaultServiceName, defaultServiceNaming, 0, 0,
                                  false, AccessLogWriter.disabled(), CommonPools.blockingTaskExecutor(),
                                  SuccessFunction.always(), 0, multipartUploadsLocation,
                                  CommonPools.workerGroup(), ImmutableList.of(), HttpHeaders.of(),
                                  ctx -> RequestId.random(), serviceErrorHandler, NOOP_CONTEXT_HOOK),
                new ServiceConfig(route2, route2,
                                  SERVICE, defaultLogName, defaultServiceName, defaultServiceNaming, 0, 0,
                                  false, AccessLogWriter.disabled(), CommonPools.blockingTaskExecutor(),
                                  SuccessFunction.always(), 0, multipartUploadsLocation,
                                  CommonPools.workerGroup(), ImmutableList.of(), HttpHeaders.of(),
                                  ctx -> RequestId.random(), serviceErrorHandler, NOOP_CONTEXT_HOOK));
        FALLBACK_SERVICE = new ServiceConfig(Route.ofCatchAll(), Route.ofCatchAll(), SERVICE,
                                             defaultLogName, defaultServiceName,
                                             defaultServiceNaming, 0, 0, false, AccessLogWriter.disabled(),
                                             CommonPools.blockingTaskExecutor(), SuccessFunction.always(), 0,
                                             multipartUploadsLocation, CommonPools.workerGroup(),
                                             ImmutableList.of(), HttpHeaders.of(), ctx -> RequestId.random(),
                                             serviceErrorHandler, NOOP_CONTEXT_HOOK);
        HOST = new VirtualHost(
                "localhost", "localhost", 0, null, SERVICES, FALLBACK_SERVICE, RejectedRouteHandler.DISABLED,
                unused -> NOPLogger.NOP_LOGGER, defaultServiceNaming, defaultLogName, 0, 0, false,
                AccessLogWriter.disabled(), CommonPools.blockingTaskExecutor(), 0, SuccessFunction.ofDefault(),
                multipartUploadsLocation, CommonPools.workerGroup(), ImmutableList.of(),
                ctx -> RequestId.random());
        ROUTER = Routers.ofVirtualHost(HOST, SERVICES, RejectedRouteHandler.DISABLED);
    }

    @Benchmark
    public Routed<ServiceConfig> exactMatch() {
        final RoutingContext ctx = DefaultRoutingContext.of(HOST, "localhost", METHOD1_REQ_TARGET,
                                                            METHOD1_HEADERS, RoutingStatus.OK,
                                                            SessionProtocol.H2C);
        final Routed<ServiceConfig> routed = ROUTER.find(ctx);
        if (routed.value() != SERVICES.get(0)) {
            throw new IllegalStateException("Routing error");
        }
        return routed;
    }

    @Benchmark
    public Routed<ServiceConfig> exactMatch_wrapped() {
        final RoutingContext ctx = new RoutingContextWrapper(
                DefaultRoutingContext.of(HOST, "localhost", METHOD1_REQ_TARGET,
                                         METHOD1_HEADERS, RoutingStatus.OK, SessionProtocol.H2C));
        final Routed<ServiceConfig> routed = ROUTER.find(ctx);
        if (routed.value() != SERVICES.get(0)) {
            throw new IllegalStateException("Routing error");
        }
        return routed;
    }
}
