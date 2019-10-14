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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpServerDefaultHeadersTest {
    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.setDefaultServerNameResponseHeader(false);
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension server3 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.setDefaultServerDateResponseHeader(false);
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension server4 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
            sb.decorator((delegate, ctx, req) -> {
                ctx.addAdditionalResponseHeader(HttpHeaderNames.SERVER, "armeria2");
                return delegate.serve(ctx, req);
            });
        }
    };

    @Test
    void testServerNameAndDateHeaderIncludedByDefault() {
        final HttpClient client = HttpClient.of(server1.httpUri("/"));
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().names()).contains(HttpHeaderNames.SERVER,
                                                   HttpHeaderNames.DATE);
        assertThat(res.headers().get(HttpHeaderNames.SERVER)).isEqualTo("armeria");
    }

    @Test
    void testServerNameHeaderShouldBeExcludedByOption() {
        final HttpClient client = HttpClient.of(server2.httpUri("/"));
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().names()).contains(HttpHeaderNames.DATE);
        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.SERVER);
    }

    @Test
    void testDateHeaderShouldBeExcludedByOption() {
        final HttpClient client = HttpClient.of(server3.httpUri("/"));
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().names()).contains(HttpHeaderNames.SERVER);
        assertThat(res.headers().get(HttpHeaderNames.SERVER)).isEqualTo("armeria");
        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.DATE);
    }

    @Test
    void testServerNameHeaderOverride() {
        final HttpClient client = HttpClient.of(server4.httpUri("/"));
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().names()).contains(HttpHeaderNames.SERVER);
        assertThat(res.headers().get(HttpHeaderNames.SERVER)).isEqualTo("armeria2");
    }
}
