/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.unsafe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AbstractPooledHttpServiceTest {

    private static final AbstractPooledHttpService IMPLEMENTED = new AbstractPooledHttpService() {

        @Override
        protected HttpResponse doOptions(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("options");
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("get");
        }

        @Override
        protected HttpResponse doHead(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("head");
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("post");
        }

        @Override
        protected HttpResponse doPut(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("put");
        }

        @Override
        protected HttpResponse doPatch(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("patch");
        }

        @Override
        protected HttpResponse doDelete(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("delete");
        }

        @Override
        protected HttpResponse doTrace(ServiceRequestContext ctx, PooledHttpRequest req) {
            return HttpResponse.of("trace");
        }
    };

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/implemented", IMPLEMENTED);
            sb.service("/not-implemented", new AbstractHttpService() {});
            sb.decorator(LoggingService.builder().newDecorator());
        }
    };

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, mode = Mode.EXCLUDE, names = {"CONNECT", "UNKNOWN"})
    void implemented(HttpMethod method) {
        final AggregatedHttpResponse response = client.execute(HttpRequest.of(method, "/implemented"))
                                                      .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // HEAD responses content stripped out by framework.
        if (method != HttpMethod.HEAD) {
            assertThat(response.contentUtf8()).isEqualTo(method.name().toLowerCase());
        }
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    void notImplemented(HttpMethod method) {
        final AggregatedHttpResponse response = client.execute(HttpRequest.of(method, "/not-implemented"))
                                                      .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
