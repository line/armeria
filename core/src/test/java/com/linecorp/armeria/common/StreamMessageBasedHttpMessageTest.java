/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageDuplicator;
import com.linecorp.armeria.common.util.EventLoopGroups;

class StreamMessageBasedHttpMessageTest {

    @ArgumentsSource(DuplicatorFactories.class)
    @ParameterizedTest
    void duplicateRequest(Function<HttpMessage, StreamMessageDuplicator<?>> duplicatorFactory) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/");
        final StreamMessage<HttpData> data = StreamMessage.of(HttpData.ofUtf8("hello"));
        final StreamMessageBasedHttpRequest req = new StreamMessageBasedHttpRequest(headers, data);
        final HttpRequestDuplicator httpRequestDuplicator =
                (HttpRequestDuplicator) duplicatorFactory.apply(req);
        final HttpRequest duplicate = httpRequestDuplicator.duplicate();
        assertThat(duplicate.headers()).isSameAs(headers);
        assertThat(duplicate.aggregate().join().content().toStringUtf8()).isEqualTo("hello");
    }

    @ArgumentsSource(DuplicatorFactories.class)
    @ParameterizedTest
    void duplicateResponse(Function<HttpMessage, StreamMessageDuplicator<?>> duplicatorFactory) {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final StreamMessage<HttpObject> objects = StreamMessage.of(headers, HttpData.ofUtf8("hello"));
        final StreamMessageBasedHttpResponse req = new StreamMessageBasedHttpResponse(objects);
        final HttpResponseDuplicator httpRequestDuplicator =
                (HttpResponseDuplicator) duplicatorFactory.apply(req);
        final HttpResponse duplicate = httpRequestDuplicator.duplicate();
        final AggregatedHttpResponse response = duplicate.aggregate().join();
        assertThat(response.headers().status()).isEqualTo(headers.status());
        assertThat(response.content().toStringUtf8()).isEqualTo("hello");
    }

    private static class DuplicatorFactories implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Function<HttpMessage, StreamMessageDuplicator<?>> f1 = StreamMessage::toDuplicator;
            final Function<HttpMessage, StreamMessageDuplicator<?>> f2 = msg -> msg.toDuplicator(100);
            final Function<HttpMessage, StreamMessageDuplicator<?>> f3 =
                    msg -> msg.toDuplicator(EventLoopGroups.directEventLoop());
            final Function<HttpMessage, StreamMessageDuplicator<?>> f4 =
                    msg -> msg.toDuplicator(EventLoopGroups.directEventLoop(), 100);

            return Stream.of(f1, f2, f3, f4).map(Arguments::of);
        }
    }
}
