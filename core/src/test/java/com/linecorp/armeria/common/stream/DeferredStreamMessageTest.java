/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.concurrent.ImmediateEventExecutor;

public class DeferredStreamMessageTest {

    @Test
    public void testInitialState() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        assertThat(m.isOpen()).isTrue();
        assertThat(m.isEmpty()).isFalse();
        assertThat(m.completionFuture()).isNotDone();
    }

    @Test
    public void testSetDelegate() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        m.delegate(new DefaultStreamMessage<>());
        assertThatThrownBy(() -> m.delegate(new DefaultStreamMessage<>()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> m.delegate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testEarlyAbort() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        m.abort();
        assertAborted(m);
        assertFailedSubscription(m, AbortedStreamException.class);
    }

    @Test
    public void testEarlyAbortWithSubscriber() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);
        m.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        m.abort();
        assertAborted(m);

        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        m.delegate(d);
        assertAborted(d);
    }

    @Test
    public void testLateAbort() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();

        m.delegate(d);
        m.abort();

        assertAborted(m);
        assertAborted(d);
    }

    @Test
    public void testLateAbortWithSubscriber() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);

        m.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        m.delegate(d);
        verify(subscriber).onSubscribe(any());

        m.abort();
        verify(subscriber, times(1)).onError(isA(AbortedStreamException.class));

        assertAborted(m);
        assertAborted(d);
    }

    @Test
    public void testEarlySubscription() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();
        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);

        m.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        assertFailedSubscription(m, IllegalStateException.class);

        m.delegate(d);
        verify(subscriber).onSubscribe(any());
    }

    @Test
    public void testLateSubscription() {
        final DeferredStreamMessage<Object> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<Object> d = new DefaultStreamMessage<>();

        m.delegate(d);

        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);

        m.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        verify(subscriber).onSubscribe(any());

        assertFailedSubscription(m, IllegalStateException.class);
    }

    private static void assertAborted(StreamMessage<?> m) {
        assertThat(m.isOpen()).isFalse();
        assertThat(m.isEmpty()).isTrue();
        assertThat(m.completionFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> m.completionFuture().get())
                .hasCauseInstanceOf(AbortedStreamException.class);
    }

    private static void assertFailedSubscription(StreamMessage<?> m, Class<? extends Throwable> causeType) {
        @SuppressWarnings("unchecked")
        final Subscriber<Object> subscriber = mock(Subscriber.class);
        m.subscribe(subscriber);
        verify(subscriber, times(1)).onError(isA(causeType));
    }

    @Test
    public void testStreaming() {
        final DeferredStreamMessage<String> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<String> d = new DefaultStreamMessage<>();
        m.delegate(d);

        final RecordingSubscriber subscriber = new RecordingSubscriber();
        final List<String> recording = subscriber.recording;

        m.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);

        assertThat(recording).containsExactly("onSubscribe");
        d.write("A");
        assertThat(recording).containsExactly("onSubscribe", "A");
        d.close();
        assertThat(recording).containsExactly("onSubscribe", "A", "onComplete");

        assertThat(m.isOpen()).isFalse();
        assertThat(m.isEmpty()).isFalse();
        assertThat(m.completionFuture()).isCompletedWithValue(null);

        assertThat(d.isOpen()).isFalse();
        assertThat(d.isEmpty()).isFalse();
        assertThat(d.completionFuture()).isCompletedWithValue(null);
    }

    @Test
    public void testStreamingError() {
        final DeferredStreamMessage<String> m = new DeferredStreamMessage<>();
        final DefaultStreamMessage<String> d = new DefaultStreamMessage<>();
        m.delegate(d);

        final RecordingSubscriber subscriber = new RecordingSubscriber();
        final List<String> recording = subscriber.recording;

        m.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);

        assertThat(recording).containsExactly("onSubscribe");
        d.write("A");
        assertThat(recording).containsExactly("onSubscribe", "A");
        final Exception exception = new Exception();
        d.close(exception);
        assertThat(recording).hasSize(3);
        assertThat(recording.get(2)).startsWith("onError: " + exception);

        assertThat(m.isOpen()).isFalse();
        assertThat(m.isEmpty()).isFalse();
        assertThat(m.completionFuture()).hasFailedWithThrowableThat().isSameAs(exception);

        assertThat(d.isOpen()).isFalse();
        assertThat(d.isEmpty()).isFalse();
        assertThat(d.completionFuture()).hasFailedWithThrowableThat().isSameAs(exception);
    }

    private static class RecordingSubscriber implements Subscriber<String> {
        final List<String> recording = new ArrayList<>();

        @Override
        public void onSubscribe(Subscription s) {
            recording.add("onSubscribe");
            s.request(1);
        }

        @Override
        public void onNext(String o) {
            recording.add(o);
        }

        @Override
        public void onError(Throwable t) {
            recording.add("onError: " + Exceptions.traceText(t));
        }

        @Override
        public void onComplete() {
            recording.add("onComplete");
        }
    }
}
