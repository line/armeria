/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServiceFinderTest {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(CorsService.builderForAnyOrigin().newDecorator());
            sb.decoratorUnder("/prefix", LoggingService.newDecorator());
            sb.decoratorUnder("/prefix/nested",
                              MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("nested")));
            sb.decoratorUnder("/unrelated", EncodingService.newDecorator());
            sb.service("/prefix/nested/service", new MyService().decorate(MyDecorator::new));
        }
    };

    @Test
    void shouldFindService() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse res = client.get("/prefix/nested/service");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        assertThat(ctx.findService(CorsService.class)).isNotNull();
        assertThat(ctx.findService(LoggingService.class)).isNotNull();
        assertThat(ctx.findService(MyDecorator.class)).isNotNull();
        assertThat(ctx.findService(MyService.class)).isNotNull();
        // Should not find the unrelated service.
        assertThat(ctx.findService(EncodingService.class)).isNull();
    }

    @Test
    void shouldFindServiceWithoutRouteDecorators() throws InterruptedException {
        final MyService myService = new MyService();
        final Server server = Server.builder()
                                    .service("/prefix/nested/service",
                                             myService.decorate(MyDecorator::new))
                                    .build();
        server.start().join();
        final BlockingWebClient client = BlockingWebClient.of("http://127.0.0.1:" + server.activeLocalPort());
        final AggregatedHttpResponse res = client.get("/prefix/nested/service");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        final ServiceRequestContext ctx = myService.lastCtx;
        assertThat(ctx).isNotNull();
        assertThat(ctx.findService(MyDecorator.class)).isNotNull();
        assertThat(ctx.findService(MyService.class)).isNotNull();

        assertThat(ctx.findService(CorsService.class)).isNull();
        server.stop();
    }

    private static class MyService implements HttpService {
        @Nullable
        private volatile ServiceRequestContext lastCtx;

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            lastCtx = ctx;
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    private static class MyDecorator extends SimpleDecoratingHttpService {
        /**
         * Creates a new instance that decorates the specified {@link HttpService}.
         */
        protected MyDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req);
        }
    }
}
