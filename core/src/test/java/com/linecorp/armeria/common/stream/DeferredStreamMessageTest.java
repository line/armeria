/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Throwables;

public class DeferredStreamMessageTest {

    @Test
    public void testInitialState() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        assertThat(m.isOpen()).isTrue();
        assertThat(m.isEmpty()).isFalse();
        assertThat(m.closeFuture()).isNotDone();
    }

    @Test
    public void testSetDelegate() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        m.delegate(new DefaultStreamMessage<>());
        assertThatThrownBy(() -> m.delegate(new DefaultStreamMessage<>()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> m.delegate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testEarlyAbort() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        m.abort();
        assertAborted(m);
        assertThatThrownBy(() -> m.subscribe(mock(Subscriber.class))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testEarlyAbortWithSubscriber() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        m.subscribe(mock(Subscriber.class));
        m.abort();
        assertAborted(m);

        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        m.delegate(d);
        assertAborted(d);
    }

    @Test
    public void testLateAbort() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();

        m.delegate(d);
        m.abort();

        assertAborted(m);
        assertAborted(d);
    }

    @Test
    public void testLateAbortWithSubscriber() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);

        m.subscribe(subscriber);
        m.delegate(d);
        verify(subscriber).onSubscribe(any());

        m.abort();
        verify(subscriber, never()).onError(any());

        assertAborted(m);
        assertAborted(d);
    }

    @Test
    public void testEarlySubscription() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);

        m.subscribe(subscriber);
        assertThatThrownBy(() -> m.subscribe(mock(Subscriber.class))).isInstanceOf(IllegalStateException.class);

        m.delegate(d);
        verify(subscriber).onSubscribe(any());
    }

    @Test
    public void testLateSubscription() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();

        m.delegate(d);

        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);

        m.subscribe(subscriber);
        verify(subscriber).onSubscribe(any());

        assertThatThrownBy(() -> m.subscribe(mock(Subscriber.class))).isInstanceOf(IllegalStateException.class);
    }

    private static void assertAborted(StreamMessage<?> m) {
        assertThat(m.isOpen()).isFalse();
        assertThat(m.isEmpty()).isTrue();
        assertThat(m.closeFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> m.closeFuture().get())
                .hasCauseInstanceOf(CancelledSubscriptionException.class);
    }

    @Test
    public void testStreaming() throws Exception {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        m.delegate(d);

        final List<Object> streamed = new ArrayList<>();
        m.subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {
                streamed.add("onSubscribe");
                s.request(1);
            }

            @Override
            public void onNext(Object o) {
                streamed.add(o);
            }

            @Override
            public void onError(Throwable t) {
                streamed.add("onError: " + Throwables.getStackTraceAsString(t));
            }

            @Override
            public void onComplete() {
                streamed.add("onComplete");
            }
        });

        assertThat(streamed).containsExactly("onSubscribe");
        d.write("A");
        assertThat(streamed).containsExactly("onSubscribe", "A");
        d.close();
        assertThat(streamed).containsExactly("onSubscribe", "A", "onComplete");

        assertThat(m.isOpen()).isFalse();
        assertThat(m.isEmpty()).isFalse();
        assertThat(m.closeFuture()).isCompletedWithValue(null);

        assertThat(d.isOpen()).isFalse();
        assertThat(d.isEmpty()).isFalse();
        assertThat(d.closeFuture()).isCompletedWithValue(null);
    }
}
