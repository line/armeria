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

import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.function.Function;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class ServerSentEventResponseConverterFunctionTest {

    private static final ResponseConverterFunction function = new ServerSentEventResponseConverterFunction();
    private static final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

    private static final ResponseHeaders EVENT_STREAM_HEADER =
            ResponseHeaders.of(HttpStatus.OK,
                               HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM);
    private static final HttpHeaders DEFAULT_TRAILERS = HttpHeaders.of();

    @Test
    public void dataStringUtf8() throws Exception {
        final HttpResponse response = doConvert(Flux.just(ServerSentEvent.ofData("foo"),
                                                          ServerSentEvent.ofData("bar")));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    .expectNext(HttpData.ofUtf8("data:foo\n\n"))
                    .expectNext(HttpData.ofUtf8("data:bar\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void withDataStringifier() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final Function<Object, String> stringifier = o -> {
            try {
                return mapper.writeValueAsString(o);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
        final HttpResponse response = doConvert(
                Flux.just(ServerSentEvent.ofData(stringifier.apply(ImmutableList.of("foo", "bar"))),
                          ServerSentEvent.ofData(stringifier.apply(ImmutableMap.of("foo", "bar")))));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    .expectNext(HttpData.ofUtf8("data:[\"foo\",\"bar\"]\n\n"))
                    .expectNext(HttpData.ofUtf8("data:{\"foo\":\"bar\"}\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void escapeLineFeedFromMultilineString() throws Exception {
        final HttpResponse response = doConvert(
                Mono.just(ServerSentEvent.builder()
                                         .id("1\n2").event("add\nadd")
                                         .comment("additional\ndescription")
                                         .data("foo\nbar").build()));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    .expectNext(HttpData.ofUtf8(":additional\n:description\nid:1\nid:2\n" +
                                                "event:add\nevent:add\ndata:foo\ndata:bar\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void emitFieldNameOnly() throws Exception {
        final HttpResponse response = doConvert(
                Flux.just(ServerSentEvent.ofComment(""),// Won't be emitted. An empty HttpData would be sent.
                          ServerSentEvent.ofId(""),
                          ServerSentEvent.ofEvent(""),
                          ServerSentEvent.ofData("")));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    .expectNext(HttpData.empty())
                    .expectNext(HttpData.ofUtf8("id\n\n"))
                    .expectNext(HttpData.ofUtf8("event\n\n"))
                    .expectNext(HttpData.ofUtf8("data\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void reconnectionTimeInMilliseconds() throws Exception {
        final HttpResponse response = doConvert(Mono.just(ServerSentEvent.ofRetry(Duration.ofSeconds(1))));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    .expectNext(HttpData.ofUtf8("retry:1000\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void publishedString() throws Exception {
        final HttpResponse response = doConvert(Flux.just("foo", "bar"));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    .expectNext(HttpData.ofUtf8("data:foo\n\n"))
                    .expectNext(HttpData.ofUtf8("data:bar\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void publishedObjects() throws Exception {
        class Foo {
            final String name;

            Foo(String name) {
                this.name = name;
            }

            @Override
            public String toString() {
                return '<' + name + '>';
            }
        }

        final HttpResponse response = doConvert(Flux.just(new Foo("bar"), new Foo("baz")));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER)
                    // String#valueOf will work by default.
                    .expectNext(HttpData.ofUtf8("data:<bar>\n\n"))
                    .expectNext(HttpData.ofUtf8("data:<baz>\n\n"))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void singleEvent() throws Exception {
        final HttpResponse response = doConvert(ServerSentEvent.ofData("foo"));
        StepVerifier.create(response)
                    .expectNext(EVENT_STREAM_HEADER.toBuilder()
                                                   .setInt(HttpHeaderNames.CONTENT_LENGTH, 10)
                                                   .build())
                    .expectNext(HttpData.ofUtf8("data:foo\n\n"))
                    .expectComplete()
                    .verify();
    }

    private static HttpResponse doConvert(Object result) throws Exception {
        return function.convertResponse(ctx, EVENT_STREAM_HEADER, result, DEFAULT_TRAILERS);
    }
}
