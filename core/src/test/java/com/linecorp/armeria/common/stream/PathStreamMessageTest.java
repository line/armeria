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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBufAllocator;

class PathStreamMessageTest {

    @Test
    void readFile() {
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
}
