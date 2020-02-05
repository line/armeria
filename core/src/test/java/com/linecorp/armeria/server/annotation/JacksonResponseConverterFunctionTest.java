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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.StepVerifier.Step;

class JacksonResponseConverterFunctionTest {

    private static final ResponseConverterFunction function = new JacksonResponseConverterFunction();
    private static final ServiceRequestContext ctx = ServiceRequestContext.builder(
            HttpRequest.of(HttpMethod.GET, "/")).build();

    // Copied from JsonTextSequences class.
    private static final byte RECORD_SEPARATOR = 0x1E;
    private static final byte LINE_FEED = 0x0A;

    private static final ResponseHeaders JSON_HEADERS =
            ResponseHeaders.of(HttpStatus.OK,
                               HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8);
    private static final ResponseHeaders JSON_SEQ_HEADERS =
            ResponseHeaders.of(HttpStatus.OK,
                               HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_SEQ);
    private static final HttpHeaders DEFAULT_TRAILERS = HttpHeaders.of();

    /**
     * Strings which are used for a publisher.
     */
    private static final String[] TEST_STRINGS = { "foo", "bar", "baz", "qux" };

    /**
     * {@link HttpData} instances which are expected to be produced by the
     * {@link JacksonResponseConverterFunction} when the function gets the
     * {@link #TEST_STRINGS} as input.
     */
    private static final HttpData[] EXPECTED_CONTENTS = {
            HttpData.wrap(new byte[] { RECORD_SEPARATOR, '\"', 'f', 'o', 'o', '\"', LINE_FEED }),
            HttpData.wrap(new byte[] { RECORD_SEPARATOR, '\"', 'b', 'a', 'r', '\"', LINE_FEED }),
            HttpData.wrap(new byte[] { RECORD_SEPARATOR, '\"', 'b', 'a', 'z', '\"', LINE_FEED }),
            HttpData.wrap(new byte[] { RECORD_SEPARATOR, '\"', 'q', 'u', 'x', '\"', LINE_FEED })
    };

    @Test
    void aggregatedJson() throws Exception {
        expectAggregatedJson(Stream.of(TEST_STRINGS))
                .expectComplete()
                .verify();
        expectAggregatedJson(Flux.fromArray(TEST_STRINGS))
                .expectComplete()
                .verify();

        // Iterable with trailers.
        final HttpHeaders trailer = HttpHeaders.of(AsciiString.of("x-trailer"), "value");
        expectAggregatedJson(Arrays.asList(TEST_STRINGS), JSON_HEADERS, trailer)
                .expectNext(trailer)
                .expectComplete()
                .verify();
    }

    @Test
    void aggregatedJson_streamError() throws Exception {
        final Stream<String> stream = Stream.of(TEST_STRINGS).map(s -> {
            throw new AnticipatedException();
        });
        final HttpResponse response =
                function.convertResponse(ctx, JSON_HEADERS, stream, DEFAULT_TRAILERS);
        StepVerifier.create(response)
                    .expectError(AnticipatedException.class)
                    .verify();
    }

    @Test
    void aggregatedJson_otherTypes() throws Exception {
        StepVerifier.create(function.convertResponse(
                ctx, JSON_HEADERS, "abc", DEFAULT_TRAILERS))
                    .expectNext(JSON_HEADERS.toBuilder()
                                            .setInt(HttpHeaderNames.CONTENT_LENGTH, 5)
                                            .build())
                    .expectNext(HttpData.ofUtf8("\"abc\""))
                    .expectComplete()
                    .verify();

        StepVerifier.create(function.convertResponse(
                ctx, JSON_HEADERS, 123, DEFAULT_TRAILERS))
                    .expectNext(JSON_HEADERS.toBuilder()
                                            .setInt(HttpHeaderNames.CONTENT_LENGTH, 3)
                                            .build())
                    .expectNext(HttpData.ofUtf8("123"))
                    .expectComplete()
                    .verify();
    }

    private static Step<HttpObject> expectAggregatedJson(Object publisherOrStream) throws Exception {
        return expectAggregatedJson(publisherOrStream, JSON_HEADERS, DEFAULT_TRAILERS);
    }

    private static Step<HttpObject> expectAggregatedJson(Object publisherOrStream,
                                                         ResponseHeaders headers,
                                                         HttpHeaders trailers) throws Exception {
        final HttpResponse response =
                function.convertResponse(ctx, headers, publisherOrStream, trailers);
        return StepVerifier.create(response)
                           .expectNext(headers)
                           .assertNext(content -> {
                               assertThatJson(((HttpData) content).toStringUtf8())
                                       .isArray().ofLength(4)
                                       .thatContains("foo")
                                       .thatContains("bar")
                                       .thatContains("baz")
                                       .thatContains("qux");
                           });
    }

    @Test
    void jsonTextSequences_stream() throws Exception {
        expectJsonSeqContents(Stream.of(TEST_STRINGS))
                .expectComplete()
                .verify();

        // Parallel stream.
        expectJsonSeqContents(Stream.of(TEST_STRINGS).parallel())
                .expectComplete()
                .verify();
    }

    @Test
    void jsonTextSequences_streamError() throws Exception {
        final Stream<String> stream = Stream.of(TEST_STRINGS).map(s -> {
            throw new AnticipatedException();
        });
        final HttpResponse response =
                function.convertResponse(ctx, JSON_SEQ_HEADERS, stream, DEFAULT_TRAILERS);
        StepVerifier.create(response)
                    .expectError(AnticipatedException.class)
                    .verify();
    }

    @Test
    void jsonTextSequences_publisher() throws Exception {
        expectJsonSeqContents(Flux.fromArray(TEST_STRINGS))
                .expectComplete()
                .verify();

        // With trailers.
        final HttpHeaders trailer = HttpHeaders.of(AsciiString.of("x-trailer"), "value");
        expectJsonSeqContents(Flux.fromArray(TEST_STRINGS), JSON_SEQ_HEADERS, trailer)
                .expectNext(trailer)
                .expectComplete()
                .verify();
    }

    @Test
    void jsonTextSequences_publisherError() throws Exception {
        StepVerifier.create(Mono.error(new AnticipatedException()))
                    .expectError(AnticipatedException.class)
                    .verify();

        final Flux<String> publisher = Flux.concat(Flux.fromArray(TEST_STRINGS),
                                                   Mono.error(new AnticipatedException()));
        expectJsonSeqContents(publisher)
                .expectError(AnticipatedException.class)
                .verify();
    }

    @Test
    void jsonTextSequences_otherTypes() throws Exception {
        StepVerifier.create(function.convertResponse(
                ctx, JSON_SEQ_HEADERS, "abc", DEFAULT_TRAILERS))
                    .expectNext(JSON_SEQ_HEADERS.toBuilder()
                                                .setInt(HttpHeaderNames.CONTENT_LENGTH, 7)
                                                .build())
                    .expectNext(HttpData.wrap(
                            new byte[] { RECORD_SEPARATOR, '\"', 'a', 'b', 'c', '\"', LINE_FEED }))
                    .expectComplete()
                    .verify();

        StepVerifier.create(function.convertResponse(
                ctx, JSON_SEQ_HEADERS, 123, DEFAULT_TRAILERS))
                    .expectNext(JSON_SEQ_HEADERS.toBuilder()
                                                .setInt(HttpHeaderNames.CONTENT_LENGTH, 5)
                                                .build())
                    .expectNext(HttpData.wrap(new byte[] { RECORD_SEPARATOR, '1', '2', '3', LINE_FEED }))
                    .expectComplete()
                    .verify();
    }

    private static Step<HttpObject> expectJsonSeqContents(Object publisherOrStream) throws Exception {
        return expectJsonSeqContents(publisherOrStream, JSON_SEQ_HEADERS, DEFAULT_TRAILERS);
    }

    private static Step<HttpObject> expectJsonSeqContents(Object publisherOrStream,
                                                          ResponseHeaders headers,
                                                          HttpHeaders trailers) throws Exception {
        final HttpResponse response =
                function.convertResponse(ctx, headers, publisherOrStream, trailers);
        return StepVerifier.create(response)
                           .expectNext(headers)
                           .expectNext(EXPECTED_CONTENTS[0])
                           .expectNext(EXPECTED_CONTENTS[1])
                           .expectNext(EXPECTED_CONTENTS[2])
                           .expectNext(EXPECTED_CONTENTS[3]);
    }
}
