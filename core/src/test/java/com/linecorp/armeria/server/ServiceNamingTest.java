/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.util.Files;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.logging.AccessLogWriter;

class ServiceNamingTest {
    @Test
    void fullTypeName_topClass() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), HealthCheckService.builder().build(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.fullTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(HealthCheckService.class.getName());
    }

    @Test
    void fullTypeName_nestedClass() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new NestedClass(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.fullTypeName().serviceName(ctx);
        assertThat(serviceName)
                .isEqualTo(ServiceNamingTest.class.getName() + '$' + NestedClass.class.getSimpleName());
    }

    @Test
    void fullTypeName_trimTrailingDollarSign() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new TrailingDollarSign$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.fullTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(ServiceNamingTest.class.getName() + "$TrailingDollarSign");
    }

    @Test
    void fullTypeName_trimTrailingDollarSignMany() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new TrailingDollarSign$$$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.fullTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(ServiceNamingTest.class.getName() + "$TrailingDollarSign");
    }

    @Test
    void fullTypeName_trimTrailingDollarSignOnly() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new $$$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.fullTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(ServiceNamingTest.class.getName());
    }

    @Test
    void simpleTypeName_topClass() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), HealthCheckService.builder().build(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.simpleTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(HealthCheckService.class.getSimpleName());
    }

    @Test
    void simpleTypeName_nestedClass() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new NestedClass(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.simpleTypeName().serviceName(ctx);
        assertThat(serviceName)
                .isEqualTo(ServiceNamingTest.class.getSimpleName() + '$' + NestedClass.class.getSimpleName());
    }

    @Test
    void simpleTypeName_trimTrailingDollarSign() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new TrailingDollarSign$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.simpleTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(ServiceNamingTest.class.getSimpleName() + "$TrailingDollarSign");
    }

    @Test
    void simpleTypeName_trimTrailingDollarSignMany() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new TrailingDollarSign$$$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.simpleTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(ServiceNamingTest.class.getSimpleName() + "$TrailingDollarSign");
    }

    @Test
    void simpleTypeName_trimTrailingDollarSignOnly() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new $$$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.simpleTypeName().serviceName(ctx);
        assertThat(serviceName).isEqualTo(ServiceNamingTest.class.getSimpleName());
    }

    @Test
    void shorten_topClass() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), HealthCheckService.builder().build(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.shorten().serviceName(ctx);
        assertThat(serviceName).isEqualTo("c.l.a.s.h." + HealthCheckService.class.getSimpleName());
    }

    @Test
    void shorten_nestedClass() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new NestedClass(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.shorten().serviceName(ctx);
        assertThat(serviceName).isEqualTo("c.l.a.s." + ServiceNamingTest.class.getSimpleName() + '$' +
                                          NestedClass.class.getSimpleName());
    }

    @Test
    void shorten_trimTrailingDollarSign() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new TrailingDollarSign$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.shorten().serviceName(ctx);
        assertThat(serviceName)
                .isEqualTo("c.l.a.s." + ServiceNamingTest.class.getSimpleName() + "$TrailingDollarSign");
    }

    @Test
    void shorten_trimTrailingDollarSignMany() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new TrailingDollarSign$$$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.shorten().serviceName(ctx);
        assertThat(serviceName)
                .isEqualTo("c.l.a.s." + ServiceNamingTest.class.getSimpleName() + "$TrailingDollarSign");
    }

    @Test
    void shorten_trimTrailingDollarSignOnly() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final ServiceConfig config =
                new ServiceConfig(Route.ofCatchAll(), new $$$(),
                                  null, null, null, 0, 0, false, AccessLogWriter.common(), false,
                                  CommonPools.blockingTaskExecutor(), true,
                                  Files.newTemporaryFolder().toPath());
        when(ctx.config()).thenReturn(config);
        final String serviceName = ServiceNaming.shorten().serviceName(ctx);
        assertThat(serviceName)
                .isEqualTo("c.l.a.s." + ServiceNamingTest.class.getSimpleName());
    }

    private static final class NestedClass implements HttpService {

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return null;
        }
    }

    @SuppressWarnings({ "DollarSignInName", "checkstyle:TypeName" })
    private static final class TrailingDollarSign$ implements HttpService {

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return null;
        }
    }

    @SuppressWarnings({ "DollarSignInName", "checkstyle:TypeName" })
    private static final class TrailingDollarSign$$$ implements HttpService {

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return null;
        }
    }

    @SuppressWarnings({ "DollarSignInName", "checkstyle:TypeName" })
    private static final class $$$ implements HttpService {

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return null;
        }
    }
}
