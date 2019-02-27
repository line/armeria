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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.Test;

import com.linecorp.armeria.testing.internal.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

public class StreamMessageDrainerTest {

    @Test
    public void defaultStreamMessage() {
        final DefaultStreamMessage<String> streamMessage = streamMessage();
        final List<String> drained = streamMessage.drainAll().join();
        assertThat(drained).containsExactly("foo", "bar", "baz");
    }

    @Test
    public void fixedStreamMessage() {
        final StreamMessage<String> streamMessage = StreamMessage.of("foo", "bar", "baz");
        final List<String> drained = streamMessage.drainAll().join();
        assertThat(drained).containsExactly("foo", "bar", "baz");
    }

    @Test
    public void deferredStreamMessage() {
        final DefaultStreamMessage<String> streamMessage = streamMessage();
        final DeferredStreamMessage<String> deferred = new DeferredStreamMessage<>();
        deferred.delegate(streamMessage);
        final List<String> drained = deferred.drainAll().join();
        assertThat(drained).containsExactly("foo", "bar", "baz");
    }

    @Test
    public void publisherBaseStreamMessage() {
        final DefaultStreamMessage<String> streamMessage = streamMessage();
        final PublisherBasedStreamMessage<String> publisherBased =
                new PublisherBasedStreamMessage<>(streamMessage);
        final List<String> drained = publisherBased.drainAll().join();
        assertThat(drained).containsExactly("foo", "bar", "baz");
    }

    @Test
    public void filteredStreamMessage() {
        final DefaultStreamMessage<String> streamMessage = streamMessage();
        final FilteredStreamMessage<String, String> filtered =
                new FilteredStreamMessage<String, String>(streamMessage) {
                    @Override
                    protected String filter(String obj) {
                        if ("foo".equals(obj)) {
                            return "qux";
                        }
                        return obj;
                    }
                };

        final List<String> drained = filtered.drainAll().join();
        assertThat(drained).containsExactly("qux", "bar", "baz");
    }

    @Test
    public void filteredStreamMessageError() {
        final DefaultStreamMessage<ByteBuf> streamMessage = new DefaultStreamMessage<>();
        final ByteBuf buf = newUnpooledBuffer();
        assertThat(buf.refCnt()).isOne();
        streamMessage.write(buf);
        streamMessage.close(new AnticipatedException());

        final FilteredStreamMessage<ByteBuf, ByteBuf> filtered =
                new FilteredStreamMessage<ByteBuf, ByteBuf>(streamMessage, true) {
                    @Override
                    protected ByteBuf filter(ByteBuf obj) {
                        return obj;
                    }
                };

        assertThatThrownBy(() -> filtered.drainAll(true).join())
                .hasCauseExactlyInstanceOf(AnticipatedException.class);
        await().untilAsserted(() -> assertThat(buf.refCnt()).isZero());
    }

    private static DefaultStreamMessage<String> streamMessage() {
        final DefaultStreamMessage<String> streamMessage = new DefaultStreamMessage<>();
        streamMessage.write("foo");
        streamMessage.write("bar");
        streamMessage.write("baz");
        streamMessage.close();
        return streamMessage;
    }

    private static ByteBuf newUnpooledBuffer() {
        return UnpooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }
}
