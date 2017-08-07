/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.Assert.fail;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

public class DefaultStreamMessageTest {

    @Test
    public void releaseOnConsumption_ByteBuf() throws Exception {
        final DefaultStreamMessage<ByteBuf> m = new DefaultStreamMessage<>();
        final ByteBuf buf = newPooledBuffer();

        assertThat(m.write(buf)).isTrue();
        assertThat(buf.refCnt()).isEqualTo(1);

        m.subscribe(new Subscriber<ByteBuf>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuf o) {
                assertThat(o).isNotSameAs(buf);
                assertThat(o).isInstanceOf(UnpooledHeapByteBuf.class);
                assertThat(o.refCnt()).isEqualTo(1);
                assertThat(buf.refCnt()).isZero();
            }

            @Override
            public void onError(Throwable throwable) {
                Exceptions.throwUnsafely(throwable);
            }

            @Override
            public void onComplete() {}
        });
    }

    @Test
    public void releaseOnConsumption_HttpData() throws Exception {
        final DefaultStreamMessage<ByteBufHolder> m = new DefaultStreamMessage<>();
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), false);

        assertThat(m.write(data)).isTrue();
        assertThat(data.refCnt()).isEqualTo(1);

        m.subscribe(new Subscriber<ByteBufHolder>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBufHolder o) {
                assertThat(o).isNotSameAs(data);
                assertThat(o).isInstanceOf(ByteBufHttpData.class);
                assertThat(o.content()).isInstanceOf(UnpooledHeapByteBuf.class);
                assertThat(o.refCnt()).isEqualTo(1);
                assertThat(data.refCnt()).isZero();
            }

            @Override
            public void onError(Throwable throwable) {
                Exceptions.throwUnsafely(throwable);
            }

            @Override
            public void onComplete() {}
        });
    }

    @Test
    public void rejectReferenceCounted() {
        final DefaultStreamMessage<Object> m = new DefaultStreamMessage<>();
        assertThatThrownBy(() -> m.write(new AbstractReferenceCounted() {
            @Override
            protected void deallocate() {}

            @Override
            public ReferenceCounted touch(Object hint) {
                return this;
            }
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void releaseWhenWritingToClosedStream_ByteBuf() {
        final DefaultStreamMessage<Object> m = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        m.close();

        assertThat(m.write(buf)).isFalse();
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    public void releaseWhenWritingToClosedStream_HttpData() {
        final DefaultStreamMessage<Object> m = new DefaultStreamMessage<>();
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        m.close();

        assertThat(m.write(data)).isFalse();
        assertThat(data.refCnt()).isZero();
    }

    @Test
    public void releaseWithZeroDemand() {
        final DefaultStreamMessage<Object> m = new DefaultStreamMessage<>();
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        m.write(data);
        m.subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                subscription.cancel();
            }

            @Override
            public void onNext(Object o) {
                fail();
            }

            @Override
            public void onError(Throwable throwable) {
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, true);

        assertThat(m.isOpen()).isFalse();
        assertThat(data.refCnt()).isZero();
    }

    @Test
    public void releaseWithZeroDemandAndClosedStream() {
        final DefaultStreamMessage<Object> m = new DefaultStreamMessage<>();
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        m.write(data);
        m.close();

        m.subscribe(new Subscriber<Object>() {
            private Subscription subscription;
            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                subscription.cancel();
            }

            @Override
            public void onNext(Object o) {
                fail();
            }

            @Override
            public void onError(Throwable throwable) {
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, true);

        assertThat(m.isOpen()).isFalse();
        assertThat(data.refCnt()).isZero();
    }

    private static ByteBuf newPooledBuffer() {
        return PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }
}
