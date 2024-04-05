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
package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class EmptyParameterWithDefaultAnnotationTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/default", new EmptyParameterService());
        }
    };

    static class EmptyParameterService {
        @Get("/string")
        public String string(@Default @Param String param) {
            return String.valueOf(param);
        }

        @Get("/integer")
        public String integer(@Default @Param Integer param) {
            return String.valueOf(param);
        }

        @Get("/primitive")
        public String primitive(@Default @Param int param) {
            return String.valueOf(param);
        }

        @Get("/string-list")
        public HttpResponse stringList(@Default @Param("param") List<String> params) {
            return HttpResponse.ofJson(params);
        }

        @Get("/integer-list")
        public HttpResponse integerList(@Default @Param("param") List<Integer> params) {
            return HttpResponse.ofJson(params);
        }

        @Get("/integer-list-with-default")
        public HttpResponse integerListWithDefault(@Default("1") @Param("param") List<Integer> params) {
            return HttpResponse.ofJson(params);
        }
    }

    private static final String NULL = "null";

    private final WebClient client = WebClient.of(server.httpUri() + "/default");

    @Test
    void testEmptyStringParameterIsEmpty() {
        AggregatedHttpResponse res = aggregate(client.get("/string?param=v"));
        assertThat(res.contentUtf8()).isEqualTo("v");

        res = aggregate(client.get("/string?param="));
        assertThat(res.contentUtf8()).isEmpty();
    }

    @Test
    void testEmptyIntegerParameter() {
        AggregatedHttpResponse res = aggregate(client.get("/integer?param=1"));
        assertThat(res.contentUtf8()).isEqualTo("1");

        res = aggregate(client.get("/integer?param="));
        assertThat(res.contentUtf8()).isEqualTo(NULL);
    }

    @Test
    void testEmptyPrimitiveParameter() {
        AggregatedHttpResponse res = aggregate(client.get("/primitive?param=1"));
        assertThat(res.contentUtf8()).isEqualTo("1");

        res = aggregate(client.get("/primitive"));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = aggregate(client.get("/primitive?param="));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testEmptyStringListParameter() {
        AggregatedHttpResponse res = aggregate(client.get("/string-list?param=v"));
        assertThat(res.contentUtf8()).isEqualTo("[\"v\"]");

        res = aggregate(client.get("/string-list?param="));
        assertThat(res.contentUtf8()).isEqualTo("[]");
    }

    @Test
    void testEmptyIntegerListParameter() {
        AggregatedHttpResponse res = aggregate(client.get("/integer-list?param=1"));
        assertThat(res.contentUtf8()).isEqualTo("[1]");

        res = aggregate(client.get("/integer-list?param="));
        assertThat(res.contentUtf8()).isEqualTo("[]");
    }

    @Test
    void testEmptyIntegerListWithDefaultParameter() {
        AggregatedHttpResponse res = aggregate(client.get("/integer-list-with-default"));
        assertThat(res.contentUtf8()).isEqualTo("[1]");

        res = aggregate(client.get("/integer-list-with-default?param="));
        assertThat(res.contentUtf8()).isEqualTo("[]");
    }

    private static AggregatedHttpResponse aggregate(HttpResponse response) {
        return response.aggregate().join();
    }
}
