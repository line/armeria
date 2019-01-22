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
package com.linecorp.armeria.server.annotation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class ByteArrayResponseConverterFunctionTest {

    private static final ResponseConverterFunction function = new ByteArrayResponseConverterFunction();
    private static final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

    private static final HttpHeaders OCTET_STREAM_HEADERS =
            HttpHeaders.of(HttpStatus.OK).contentType(MediaType.OCTET_STREAM);
    private static final HttpHeaders DEFAULT_TRAILING_HEADERS = HttpHeaders.EMPTY_HEADERS;

    @BeforeClass
    public static void setup() {
        when(ctx.blockingTaskExecutor()).thenReturn(MoreExecutors.newDirectExecutorService());
    }

    @Test
    public void streaming_HttpData() throws Exception {
        final List<HttpData> contents = ImmutableList.of(HttpData.ofUtf8("foo"),
                                                         HttpData.ofUtf8("bar"),
                                                         HttpData.ofUtf8("baz"));
        for (final Object result : ImmutableList.of(Flux.fromIterable(contents),
                                                    contents.stream())) {
            StepVerifier.create(from(result))
                        .expectNext(OCTET_STREAM_HEADERS)
                        .expectNext(contents.get(0))
                        .expectNext(contents.get(1))
                        .expectNext(contents.get(2))
                        .expectComplete()
                        .verify();
        }

        StepVerifier.create(from(contents.get(0)))
                    .expectNext(OCTET_STREAM_HEADERS)
                    .expectNext(contents.get(0))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void streaming_byteArray() throws Exception {
        final List<byte[]> contents = ImmutableList.of("foo".getBytes(),
                                                       "bar".getBytes(),
                                                       "baz".getBytes());
        for (final Object result : ImmutableList.of(Flux.fromIterable(contents),
                                                    contents.stream())) {
            StepVerifier.create(from(result))
                        .expectNext(OCTET_STREAM_HEADERS)
                        .expectNext(HttpData.of(contents.get(0)))
                        .expectNext(HttpData.of(contents.get(1)))
                        .expectNext(HttpData.of(contents.get(2)))
                        .expectComplete()
                        .verify();
        }

        StepVerifier.create(from(contents.get(0)))
                    .expectNext(OCTET_STREAM_HEADERS)
                    .expectNext(HttpData.of(contents.get(0)))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void streaming_unsupportedType() throws Exception {
        final String unsupported = "Unsupported type.";

        StepVerifier.create(from(Mono.just(unsupported)))
                    .expectError(IllegalStateException.class)
                    .verify();

        StepVerifier.create(from(Stream.of(unsupported)))
                    .expectError(IllegalStateException.class)
                    .verify();

        assertThatThrownBy(() -> from(unsupported)).isInstanceOf(FallthroughException.class);
    }

    private static HttpResponse from(Object result) throws Exception {
        return function.convertResponse(ctx, OCTET_STREAM_HEADERS, result, DEFAULT_TRAILING_HEADERS);
    }

    @Test
    public void withoutContentType() throws Exception {
        StepVerifier.create(function.convertResponse(ctx, HttpHeaders.of(HttpStatus.OK),
                                                     HttpData.ofUtf8("foo"), DEFAULT_TRAILING_HEADERS))
                    // 'application/octet-stream' should be added.
                    .expectNext(OCTET_STREAM_HEADERS)
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectComplete()
                    .verify();

        StepVerifier.create(function.convertResponse(ctx, HttpHeaders.of(HttpStatus.OK),
                                                     "foo".getBytes(), DEFAULT_TRAILING_HEADERS))
                    .expectNext(OCTET_STREAM_HEADERS)
                    .expectNext(HttpData.of("foo".getBytes()))
                    .expectComplete()
                    .verify();

        assertThatThrownBy(() -> from("Unsupported type.")).isInstanceOf(FallthroughException.class);
    }
}
