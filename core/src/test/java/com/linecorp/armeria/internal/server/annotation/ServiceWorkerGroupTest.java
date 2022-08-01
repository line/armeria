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
 * under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;

class ServiceWorkerGroupTest {
    private static final EventLoopGroup aExecutor = new DefaultEventLoopGroup(1);
    private static final EventLoopGroup bExecutor = new DefaultEventLoopGroup(1);
    private static final EventLoopGroup defaultExecutor = new DefaultEventLoopGroup(1);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService().serviceWorkerGroup(aExecutor, true)
              .build(new MyAnnotatedServiceA());
            sb.annotatedService().serviceWorkerGroup(bExecutor, false)
              .build(new MyAnnotatedServiceB());
            sb.annotatedService(new MyAnnotatedServiceC());

            sb.serviceWorkerGroup(defaultExecutor, true);
        }
    };

    @Test
    void testServiceWorkerGroup() {
        final VirtualHost defaultVH = server.server().config().defaultVirtualHost();
        assertThat(defaultVH.serviceWorkerGroup()).isSameAs(defaultExecutor);

        final BlockingWebClient client = server.webClient().blocking();

        AggregatedHttpResponse res = client.execute(RequestHeaders.of(HttpMethod.GET, "/a"));
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        res = client.execute(RequestHeaders.of(HttpMethod.GET, "/b"));
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        res = client.execute(RequestHeaders.of(HttpMethod.GET, "/c"));
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        assertThat(aExecutor.isShutdown()).isFalse();
        assertThat(bExecutor.isShutdown()).isFalse();
        assertThat(defaultExecutor.isShutdown()).isFalse();

        server.stop().join();

        assertThat(aExecutor.isShutdown()).isTrue();
        assertThat(bExecutor.isShutdown()).isFalse();
        assertThat(defaultExecutor.isShutdown()).isTrue();
    }

    static class MyAnnotatedServiceA {
        @Get("/a")
        public HttpResponse httpResponse(ServiceRequestContext ctx) {
            assertThat(ctx.eventLoop().withoutContext()).isSameAs(aExecutor.next());
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class MyAnnotatedServiceB {
        @Get("/b")
        public HttpResponse httpResponse(ServiceRequestContext ctx) {
            assertThat(ctx.eventLoop().withoutContext()).isSameAs(bExecutor.next());
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class MyAnnotatedServiceC {
        @Get("/c")
        public HttpResponse httpResponse(ServiceRequestContext ctx) {
            assertThat(ctx.eventLoop().withoutContext()).isSameAs(defaultExecutor.next());
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
