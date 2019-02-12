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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.internal.AnticipatedException;

import io.netty.util.AsciiString;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.StepVerifier.Step;

public class JacksonResponseConverterFunctionTest {

    private static final ResponseConverterFunction function = new JacksonResponseConverterFunction();
    private static final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

    // Copied from JsonTextSequences class.
    private static final byte RECORD_SEPARATOR = 0x1E;
    private static final byte LINE_FEED = 0x0A;

    private static final HttpHeaders JSON_HEADERS =
            HttpHeaders.of(HttpStatus.OK).contentType(MediaType.JSON_UTF_8);
    private static final HttpHeaders JSON_SEQ_HEADERS =
            HttpHeaders.of(HttpStatus.OK).contentType(MediaType.JSON_SEQ);
    private static final HttpHeaders DEFAULT_TRAILING_HEADERS = HttpHeaders.EMPTY_HEADERS;

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
            HttpData.of(new byte[] { RECORD_SEPARATOR, '\"', 'f', 'o', 'o', '\"', LINE_FEED }),
            HttpData.of(new byte[] { RECORD_SEPARATOR, '\"', 'b', 'a', 'r', '\"', LINE_FEED }),
            HttpData.of(new byte[] { RECORD_SEPARATOR, '\"', 'b', 'a', 'z', '\"', LINE_FEED }),
            HttpData.of(new byte[] { RECORD_SEPARATOR, '\"', 'q', 'u', 'x', '\"', LINE_FEED })
    };

    @BeforeClass
    public static void setup() {
        when(ctx.blockingTaskExecutor()).thenReturn(MoreExecutors.newDirectExecutorService());
    }

    @Test
    public void aggregatedJson() throws Exception {
        expectAggregatedJson(Stream.of(TEST_STRINGS))
                .expectComplete()
                .verify();
        expectAggregatedJson(Flux.fromArray(TEST_STRINGS))
                .expectComplete()
                .verify();

        // Iterable with trailing headers.
        final HttpHeaders trailer = HttpHeaders.of(AsciiString.of("x-trailer"), "value");
        expectAggregatedJson(Arrays.asList(TEST_STRINGS), JSON_HEADERS, trailer)
                .expectNext(trailer)
                .expectComplete()
                .verify();
    }

    @Test
    public void aggregatedJson_streamError() throws Exception {
        final Stream<String> stream = Stream.of(TEST_STRINGS).map(s -> {
            throw new AnticipatedException();
        });
        final HttpResponse response =
                function.convertResponse(ctx, JSON_HEADERS, stream, DEFAULT_TRAILING_HEADERS);
        StepVerifier.create(response)
                    .expectError(AnticipatedException.class)
                    .verify();
    }

    @Test
    public void aggregatedJson_otherTypes() throws Exception {
        StepVerifier.create(function.convertResponse(
                ctx, JSON_HEADERS, "abc", DEFAULT_TRAILING_HEADERS))
                    .expectNext(JSON_HEADERS)
                    .expectNext(HttpData.ofUtf8("\"abc\""))
                    .expectComplete()
                    .verify();

        StepVerifier.create(function.convertResponse(
                ctx, JSON_HEADERS, 123, DEFAULT_TRAILING_HEADERS))
                    .expectNext(JSON_HEADERS)
                    .expectNext(HttpData.ofUtf8("123"))
                    .expectComplete()
                    .verify();
    }

    private Step<HttpObject> expectAggregatedJson(Object publisherOrStream) throws Exception {
        return expectAggregatedJson(publisherOrStream, JSON_HEADERS, DEFAULT_TRAILING_HEADERS);
    }

    private Step<HttpObject> expectAggregatedJson(Object publisherOrStream,
                                                  HttpHeaders headers,
                                                  HttpHeaders trailingHeaders) throws Exception {
        final HttpResponse response =
                function.convertResponse(ctx, headers, publisherOrStream, trailingHeaders);
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
    public void jsonTextSequences_stream() throws Exception {
        expectJsonSeqContents(Stream.of(TEST_STRINGS))
                .expectComplete()
                .verify();

        // Parallel stream.
        expectJsonSeqContents(Stream.of(TEST_STRINGS).parallel())
                .expectComplete()
                .verify();
    }

    @Test
    public void jsonTextSequences_streamError() throws Exception {
        final Stream<String> stream = Stream.of(TEST_STRINGS).map(s -> {
            throw new AnticipatedException();
        });
        final HttpResponse response =
                function.convertResponse(ctx, JSON_SEQ_HEADERS, stream, DEFAULT_TRAILING_HEADERS);
        StepVerifier.create(response)
                    .expectError(AnticipatedException.class)
                    .verify();
    }

    @Test
    public void jsonTextSequences_publisher() throws Exception {
        expectJsonSeqContents(Flux.fromArray(TEST_STRINGS))
                .expectComplete()
                .verify();

        // With trailing headers.
        final HttpHeaders trailer = HttpHeaders.of(AsciiString.of("x-trailer"), "value");
        expectJsonSeqContents(Flux.fromArray(TEST_STRINGS), JSON_SEQ_HEADERS, trailer)
                .expectNext(trailer)
                .expectComplete()
                .verify();
    }

    @Test
    public void jsonTextSequences_publisherError() throws Exception {
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
    public void jsonTextSequences_otherTypes() throws Exception {
        StepVerifier.create(function.convertResponse(
                ctx, JSON_SEQ_HEADERS, "abc", DEFAULT_TRAILING_HEADERS))
                    .expectNext(JSON_SEQ_HEADERS)
                    .expectNext(HttpData.of(
                            new byte[] { RECORD_SEPARATOR, '\"', 'a', 'b', 'c', '\"', LINE_FEED }))
                    .expectComplete()
                    .verify();

        StepVerifier.create(function.convertResponse(
                ctx, JSON_SEQ_HEADERS, 123, DEFAULT_TRAILING_HEADERS))
                    .expectNext(JSON_SEQ_HEADERS)
                    .expectNext(HttpData.of(
                            new byte[] { RECORD_SEPARATOR, '1', '2', '3', LINE_FEED }))
                    .expectComplete()
                    .verify();
    }

    private Step<HttpObject> expectJsonSeqContents(Object publisherOrStream) throws Exception {
        return expectJsonSeqContents(publisherOrStream, JSON_SEQ_HEADERS, DEFAULT_TRAILING_HEADERS);
    }

    private Step<HttpObject> expectJsonSeqContents(Object publisherOrStream,
                                                   HttpHeaders headers,
                                                   HttpHeaders trailingHeaders) throws Exception {
        final HttpResponse response =
                function.convertResponse(ctx, headers, publisherOrStream, trailingHeaders);
        return StepVerifier.create(response)
                           .expectNext(headers)
                           .expectNext(EXPECTED_CONTENTS[0])
                           .expectNext(EXPECTED_CONTENTS[1])
                           .expectNext(EXPECTED_CONTENTS[2])
                           .expectNext(EXPECTED_CONTENTS[3]);
    }
}
