/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import reactor.test.StepVerifier;

class AggregatingStreamMessageTest {

    @Test
    void shouldCloseBeforeSubscribing() {
        final AggregatingStreamMessage<Integer> stream = new AggregatingStreamMessage<>(4);
        stream.write(1);
        stream.write(2);
        stream.write(3);
        StepVerifier.create(stream)
                    .thenRequest(1)
                    .expectErrorSatisfies(ex -> {
                        assertThat(ex).isInstanceOf(IllegalStateException.class)
                                      .hasMessage("a fixed stream is not closed yet");
                    })
                    .verify();
    }

    @Test
    void testOpen() {
        final AggregatingStreamMessage<Integer> stream = new AggregatingStreamMessage<>(1);
        assertThat(stream.isOpen()).isTrue();
        stream.write(1);
        assertThat(stream.isOpen()).isTrue();
        stream.close();
        assertThat(stream.isOpen()).isFalse();
    }

    @Test
    void testEmpty() {
        final AggregatingStreamMessage<Integer> stream = new AggregatingStreamMessage<>(1);
        assertThat(stream.isEmpty()).isTrue();
        stream.write(1);
        assertThat(stream.isEmpty()).isFalse();
    }

    @Test
    void releaseObjectsOnAbortion() {
        final ByteBuf byteBuf1 = newBuffer("obj1");
        final ByteBuf byteBuf2 = newBuffer("obj2");
        final AggregatingStreamMessage<HttpData> stream = new AggregatingStreamMessage<>(1);
        stream.write(HttpData.wrap(byteBuf1));
        stream.write(HttpData.wrap(byteBuf2));
        stream.abort();
        await().untilAsserted(() -> {
            assertThat(byteBuf1.refCnt()).isZero();
            assertThat(byteBuf2.refCnt()).isZero();
        });
    }

    private static ByteBuf newBuffer(String content) {
        return ByteBufAllocator.DEFAULT.buffer().writeBytes(content.getBytes());
    }
}
