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
package com.linecorp.armeria.internal.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpServiceAnnotationAliasTest {

    @Retention(RetentionPolicy.RUNTIME)
    @ResponseConverter(MyResponseConverter.class)
    @Produces("text/plain")
    @interface MyResponse {}

    static class MyResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, HttpHeaders headers,
                                            @Nullable Object result, HttpHeaders trailingHeaders)
                throws Exception {
            return HttpResponse.of(
                    headers, HttpData.ofUtf8("Hello, %s!", result), trailingHeaders);
        }
    }

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/hello/{name}")
                @MyResponse
                public String name(@Param String name) {
                    return name;
                }
            });
        }
    };

    @Test
    public void serviceConfiguredWithAlias() {
        final AggregatedHttpMessage msg = HttpClient.of(rule.uri("/")).get("/hello/Armeria")
                                                    .aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.parse("text/plain"));
        assertThat(msg.content().toStringUtf8()).isEqualTo("Hello, Armeria!");
    }
}
