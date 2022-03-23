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

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceExchangeTypeTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new MyAnnotatedService());
        }
    };

    @Test
    void exchangeType() {
        AggregatedHttpResponse response =
                WebClient.of(server.httpUri()).post("/response-streaming", "foo").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo(ExchangeType.RESPONSE_STREAMING.toString());

        response = WebClient.of(server.httpUri()).post("/bidi-streaming", "foo").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo(ExchangeType.BIDI_STREAMING.toString());
    }

    private static final class MyAnnotatedService {
        @Post
        @Path("/response-streaming")
        public HttpResponse responseStreaming(AggregatedHttpRequest request, ServiceRequestContext ctx) {
            final ExchangeType exchangeType = exchangeType(ctx);
            return HttpResponse.of(exchangeType.toString());
        }

        @Post
        @Path("/bidi-streaming")
        public HttpResponse bidiStreaming(ServiceRequestContext ctx) {
            final ExchangeType exchangeType = exchangeType(ctx);
            return HttpResponse.of(exchangeType.toString());
        }

        private static ExchangeType exchangeType(ServiceRequestContext ctx) {
            return ctx.config().service().exchangeType(ctx.request().headers(), ctx.config().route());
        }
    }
}
