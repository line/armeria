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

package com.linecorp.armeria.common.stream;

import static com.linecorp.armeria.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBufAllocator;

class PathStreamMessageTest {

    @ArgumentsSource(SubscriptionOptionsProvider.class)
    @ParameterizedTest
    void readFile(SubscriptionOption[] options) {
        final Path path = Paths.get("src/test/resources/com/linecorp/armeria/common/stream/test.txt");
        final StreamMessage<HttpData> publisher = StreamMessage.of(path, ByteBufAllocator.DEFAULT, 12);
        final AtomicBoolean completed = new AtomicBoolean();
        final StringBuilder stringBuilder = new StringBuilder();
        publisher.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                final String str = httpData.toStringUtf8();
                assertThat(str.length()).isLessThanOrEqualTo(12);

                assertThat(httpData.isPooled()).isEqualTo(containsWithPooledObjects(options));
                httpData.close();
                stringBuilder.append(str);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, options);

        await().untilTrue(completed);
        assertThat(stringBuilder.toString())
                .isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @CsvSource({ "1", "12", "128" })
    @ParameterizedTest
    void differentBufferSize(int bufferSize) {
        final Path path = Paths.get("src/test/resources/com/linecorp/armeria/common/stream/test.txt");
        final StreamMessage<HttpData> publisher = StreamMessage.of(path, ByteBufAllocator.DEFAULT, bufferSize);

        final StringBuilder stringBuilder = new StringBuilder();
        final AtomicBoolean completed = new AtomicBoolean();
        publisher.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                final String str = httpData.toStringUtf8();
                assertThat(str.length()).isLessThanOrEqualTo(bufferSize);
                stringBuilder.append(str);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        await().untilTrue(completed);
        assertThat(stringBuilder.toString())
                .isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    private static class SubscriptionOptionsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(
                    Arguments.of((Object) EMPTY_OPTIONS),
                    Arguments.of((Object) new SubscriptionOption[]{ SubscriptionOption.WITH_POOLED_OBJECTS }));
        }
    }
}
