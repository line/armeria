/*
 * Copyright 2018 LINE Corporation
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

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

// TODO(hyangtack) This test will be removed once @RequestObject is removed.
public class AnnotatedHttpServiceRequestObjectTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Service(), LoggingService.newDecorator());
        }
    };

    private static class Service {
        @Post("/test1")
        public String test1(@RequestObject JsonBean bean) {
            // Using a default JSON request converter.
            return bean.value();
        }

        @Get("/test2/:value")
        public String test2(@RequestObject ReqBean bean) {
            // Using a default bean converter.
            return bean.value();
        }

        @Get("/test3/:value")
        public String test3(@RequestObject(MyRequestConverter.class) String value) {
            // Using a user-defined converter.
            return value;
        }

        @Get("/test4/:value")
        @RequestConverter(MyRequestConverter.class)
        public String test4(@RequestObject String value) {
            // Using a user-defined converter.
            return value;
        }
    }

    private static class JsonBean {
        private String value;

        @JsonCreator
        JsonBean(@JsonProperty("value") String value) {
            this.value = value;
        }

        @JsonProperty
        public String value() {
            return value;
        }
    }

    private static class ReqBean {
        @Param
        private String value;

        public String value() {
            return value;
        }
    }

    private static class MyRequestConverter implements RequestConverterFunction {
        @Nullable
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) throws Exception {
            return ctx.pathParam("value");
        }
    }

    @Test
    public void testBackwardCompatibility() {
        final HttpClient client = HttpClient.of(rule.uri("/"));

        AggregatedHttpMessage response;
        response = client.execute(HttpHeaders.of(HttpMethod.POST, "/test1")
                                             .contentType(MediaType.JSON_UTF_8),
                                  "{\"value\": \"json\"}").aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("json");

        response = client.get("/test2/bean").aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("bean");

        response = client.get("/test3/converter1").aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("converter1");

        response = client.get("/test4/converter2").aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("converter2");
    }
}
