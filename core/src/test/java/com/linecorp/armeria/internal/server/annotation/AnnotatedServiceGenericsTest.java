/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.MatchesParam;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceGenericsTest {

    private static final BlockingQueue<RequestLogAccess> logs = new LinkedTransferQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/ints")
                public String getInts(@Param("v") List<Integer> values) {
                    return join(values, Integer.class);
                }

                @Get("/ints")
                @MatchesParam("optional=true")
                public String getInts(@Param("v") Optional<List<Integer>> values) {
                    return join(values.orElse(ImmutableList.of()), Integer.class);
                }

                @Post("/ints")
                public String postInts(List<Integer> values) {
                    return join(values, Integer.class);
                }

                @Post("/ints")
                @MatchesParam("optional=true")
                public String postInts(Optional<List<Integer>> values) {
                    return join(values.orElse(ImmutableList.of()), Integer.class);
                }

                @Get("/longs")
                public String getLongs(@Param("v") List<Long> values) {
                    return join(values, Long.class);
                }

                @Get("/longs")
                @MatchesParam("optional=true")
                public String getLongs(@Param("v") Optional<List<Long>> values) {
                    return join(values.orElse(ImmutableList.of()), Long.class);
                }

                @Post("/longs")
                public String postLongs(List<Long> values) {
                    return join(values, Long.class);
                }

                @Post("/longs")
                @MatchesParam("optional=true")
                public String postLongs(Optional<List<Long>> values) {
                    return join(values.orElse(ImmutableList.of()), Long.class);
                }

                private String join(Iterable<?> values, Class<?> elementType) {
                    values.forEach(e -> assertThat(e).isInstanceOf(elementType));
                    return Joiner.on(':').join(values);
                }
            });

            sb.decorator((delegate, ctx, req) -> {
                logs.add(ctx.log());
                return delegate.serve(ctx, req);
            });

            // The annotated service will not be invoked at all for '/fail_early'.
            sb.routeDecorator().path("/fail_early")
              .build((delegate, ctx, req) -> {
                  throw HttpStatusException.of(500);
              });
        }
    };

    @ParameterizedTest
    @CsvSource({
            "/ints,  true",
            "/ints,  false",
            "/longs, true",
            "/longs, false"
    })
    void testGet(String path, boolean optional) throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        assertThat(client.get(path + "?v=1&v=2&v=3" + (optional ? "&optional=true" : ""))
                         .contentUtf8()).isEqualTo("1:2:3");
    }

    @ParameterizedTest
    @CsvSource({
            "/ints,  true",
            "/ints,  false",
            "/longs, true",
            "/longs, false"
    })
    void testPost(String path, boolean optional) throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        assertThat(client.execute(RequestHeaders.of(HttpMethod.POST, path + (optional ? "?optional=true" : ""),
                                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8),
                                  HttpData.ofUtf8("[1,2,3]"))
                         .contentUtf8()).isEqualTo("1:2:3");
    }
}
