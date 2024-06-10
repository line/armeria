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

import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;
import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.util.Files;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.logging.AccessLogWriter;

public class ServiceTest {

    /**
     * Tests if a user can write a decorator with working as() and serviceAdded() using lambda expressions only.
     */
    @Test
    void lambdaExpressionDecorator() throws Exception {
        final FooService inner = new FooService();
        final HttpService outer = inner.decorate((delegate, ctx, req) -> {
            final HttpRequest newReq = HttpRequest.of(HttpMethod.GET, "/");
            return delegate.serve(ctx, newReq);
        });

        assertDecoration(inner, outer);
    }

    private static void assertDecoration(FooService inner, HttpService outer) throws Exception {

        // Test if Service.as() works as expected.
        assertThat(outer.as(serviceType(inner))).isSameAs(inner);
        assertThat(outer.as(serviceType(outer))).isSameAs(outer);
        assertThat(outer.as(String.class)).isNull();

        // Test if FooService.serviceAdded() is invoked.
        final ServiceConfig cfg =
                new ServiceConfig(Route.ofCatchAll(), Route.ofCatchAll(),
                                  outer, /* defaultLogName */ null, /* defaultServiceName */ null,
                                  ServiceNaming.of("FooService"), 1, 1, true,
                                  AccessLogWriter.disabled(),
                                  CommonPools.blockingTaskExecutor(),
                                  SuccessFunction.always(),
                                  0, Files.newTemporaryFolder().toPath(),
                                  MultipartRemovalStrategy.ON_RESPONSE_COMPLETION, CommonPools.workerGroup(),
                                  ImmutableList.of(), HttpHeaders.of(),
                                  ctx -> RequestId.of(1L),
                                  ServerErrorHandler.ofDefault().asServiceErrorHandler(), NOOP_CONTEXT_HOOK);
        outer.serviceAdded(cfg);
        assertThat(inner.cfg).isSameAs(cfg);
    }

    @SuppressWarnings("unchecked")
    private static Class<Service<?, ?>> serviceType(Service<?, ?> service) {
        return (Class<Service<?, ?>>) service.getClass();
    }

    private static final class FooService implements HttpService {

        @Nullable
        ServiceConfig cfg;

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            this.cfg = cfg;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            // Will never reach here.
            throw new Error();
        }
    }

    public static class FooServiceDecorator extends SimpleDecoratingHttpService {
        public FooServiceDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req);
        }
    }

    public static class BadFooServiceDecorator extends FooServiceDecorator {
        public BadFooServiceDecorator(HttpService delegate, @SuppressWarnings("unused") Object unused) {
            super(delegate);
        }
    }
}
