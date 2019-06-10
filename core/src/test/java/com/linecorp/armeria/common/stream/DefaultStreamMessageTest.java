/*
 * Copyright 2017 LINE Corporation
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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.testing.junit.common.EventLoopExtension;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.EventExecutor;

class DefaultStreamMessageTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    /**
     * Makes sure {@link Subscriber#onComplete()} is always invoked after
     * {@link Subscriber#onSubscribe(Subscription)} even if
     * {@link StreamMessage#subscribe(Subscriber, EventExecutor)} is called from non-{@link EventLoop}.
     */
    @Test
    void onSubscribeBeforeOnComplete() throws Exception {
        final BlockingQueue<String> queue = new LinkedTransferQueue<>();
        // Repeat to increase the chance of reproduction.
        for (int i = 0; i < 8192; i++) {
            final StreamMessageAndWriter<Integer> stream = new DefaultStreamMessage<>();
            eventLoop.get().execute(stream::close);
            stream.subscribe(new Subscriber<Object>() {
                @Override
                public void onSubscribe(Subscription s) {
                    queue.add("onSubscribe");
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Object o) {
                    queue.add("onNext");
                }

                @Override
                public void onError(Throwable t) {
                    queue.add("onError");
                }

                @Override
                public void onComplete() {
                    queue.add("onComplete");
                }
            }, eventLoop.get());

            assertThat(queue.poll(5, TimeUnit.SECONDS)).isEqualTo("onSubscribe");
            assertThat(queue.poll(5, TimeUnit.SECONDS)).isEqualTo("onComplete");
        }
    }

    @Test
    void rejectReferenceCounted() {
        final AbstractReferenceCounted item = new AbstractReferenceCounted() {
            @Override
            protected void deallocate() {}

            @Override
            public ReferenceCounted touch(Object hint) {
                return this;
            }
        };
        final StreamMessageAndWriter<Object> stream = new DefaultStreamMessage<>();
        assertThatThrownBy(() -> stream.write(item)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releaseWhenWritingToClosedStream_ByteBuf() {
        final StreamMessageAndWriter<Object> stream = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().retain();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        assertThat(stream.tryWrite(buf)).isFalse();
        assertThat(buf.refCnt()).isOne();
        assertThatThrownBy(() -> stream.write(buf)).isInstanceOf(ClosedPublisherException.class);
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void releaseWhenWritingToClosedStream_ByteBuf_Supplier() {
        final StreamMessageAndWriter<Object> stream = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().retain();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        assertThat(stream.tryWrite(() -> buf)).isFalse();
        assertThat(buf.refCnt()).isOne();
        assertThatThrownBy(() -> stream.write(() -> buf)).isInstanceOf(ClosedPublisherException.class);
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void releaseWhenWritingToClosedStream_HttpData() {
        final StreamMessageAndWriter<Object> stream = new DefaultStreamMessage<>();
        final ByteBufHttpData data = new ByteBufHttpData(
                PooledByteBufAllocator.DEFAULT.buffer().writeByte(0), true).retain();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        assertThat(stream.tryWrite(data)).isFalse();
        assertThat(data.refCnt()).isOne();
        assertThatThrownBy(() -> stream.write(data)).isInstanceOf(ClosedPublisherException.class);
        assertThat(data.refCnt()).isZero();
    }

    @Test
    void releaseWhenWritingToClosedStream_HttpData_Supplier() {
        final StreamMessageAndWriter<Object> stream = new DefaultStreamMessage<>();
        final ByteBufHttpData data = new ByteBufHttpData(
                PooledByteBufAllocator.DEFAULT.buffer().writeByte(0), true).retain();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        assertThat(stream.tryWrite(() -> data)).isFalse();
        assertThat(data.refCnt()).isOne();
        assertThatThrownBy(() -> stream.write(() -> data)).isInstanceOf(ClosedPublisherException.class);
        assertThat(data.refCnt()).isZero();
    }
}
