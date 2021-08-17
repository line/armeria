/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class StreamingCallSubscriberTest {

    private static class ManualMockCallback implements Callback {
        private int callbackCallingCount;
        @Nullable
        private Response response;
        @Nullable
        private IOException exception;

        @Override
        public void onFailure(Call call, IOException e) {
            callbackCallingCount++;
            exception = e;
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            callbackCallingCount++;
            this.response = response;
        }
    }

    @Rule
    public final MockitoRule mockingRule = MockitoJUnit.rule();

    @Mock
    @Nullable
    ArmeriaCall armeriaCall;

    @Mock
    @Nullable
    Subscription subscription;

    @Test
    public void completeNormally() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(true);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://foo.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(200));
        subscriber.onNext(HttpData.ofUtf8("{\"name\":\"foo\"}"));
        subscriber.onComplete();

        verify(subscription, times(2)).request(1L);
        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));
        await().untilAsserted(() -> assertThat(callback.response.header(":status")).isEqualTo("200"));
        await().untilAsserted(
                () -> assertThat(callback.response.body().string()).isEqualTo("{\"name\":\"foo\"}"));
    }

    @Test
    public void completeOnlyHeaders() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(true);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://foo.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(HttpStatus.OK, CONTENT_LENGTH, 0));
        subscriber.onComplete();

        verify(subscription, times(2)).request(1L);
        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));
        await().untilAsserted(
                () -> assertThat(callback.response.header(CONTENT_LENGTH.toString())).isEqualTo("0"));
        await().untilAsserted(() -> assertThat(callback.response.body().string()).isEmpty());
    }

    @Test
    public void dataIsIgnoredAfterTrailers() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(true);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://bar.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(100));
        subscriber.onNext(ResponseHeaders.of(200));
        subscriber.onNext(HttpHeaders.of(HttpHeaderNames.of("foo"), "bar")); // Trailers.
        subscriber.onNext(HttpData.ofUtf8("baz")); // Ignored.
        subscriber.onComplete();

        verify(subscription, times(4)).request(1L);

        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));

        // TODO(minwoox) Remove after we can retrieve trailers.
        TimeUnit.SECONDS.sleep(2);

        assertThat(callback.response.header("foo")).isNull(); // Currently, there's no way to retrieve trailers.
        assertThat(callback.response.body().string()).isEmpty();
    }

    @Test
    public void cancel() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(false);
        when(armeriaCall.isCanceled()).thenReturn(false, false, true);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://foo.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(200));
        subscriber.onNext(HttpData.ofUtf8("{\"name\":\"foo\"}"));
        subscriber.onComplete();

        verify(subscription, times(2)).request(1L);

        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));
        await().untilAsserted(() -> assertThat(callback.exception.getMessage()).isEqualTo("cancelled"));
    }

    @Test
    public void cancel_duringReadingData() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(false);
        when(armeriaCall.isCanceled()).thenReturn(false, false, false, true);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://foo.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(200));
        subscriber.onNext(HttpData.ofUtf8("{\"name\":"));
        subscriber.onNext(HttpData.ofUtf8("\"foo\"}"));
        subscriber.onComplete();

        verify(subscription, times(2)).request(1L);

        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));
        await().untilAsserted(() -> assertThat(callback.exception).isNull());
        await().untilAsserted(
                () -> assertThatThrownBy(() -> callback.response.body().string()).hasMessage("closed"));
    }

    @Test
    public void exception_beforeReceivingHttpData() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(true);
        when(armeriaCall.isCanceled()).thenReturn(false);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://foo.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(200));
        subscriber.onError(new IOException("foo"));

        verify(subscription, times(2)).request(1L);
        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));
        assertThat(callback.exception).hasMessageEndingWith("foo");
    }

    @Test
    public void exception_afterReceivingHttpData() throws Exception {
        when(armeriaCall.tryFinish()).thenReturn(true);
        when(armeriaCall.isCanceled()).thenReturn(false);

        final ManualMockCallback callback = new ManualMockCallback();
        final StreamingCallSubscriber subscriber = new StreamingCallSubscriber(
                armeriaCall, callback, new Request.Builder().url("http://foo.com").build(),
                MoreExecutors.directExecutor());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(ResponseHeaders.of(200));
        subscriber.onNext(HttpData.ofUtf8("{\"name\":"));
        subscriber.onError(new IOException("foo"));

        verify(subscription, times(2)).request(1L);

        await().untilAsserted(() -> assertThat(callback.callbackCallingCount).isEqualTo(1));
        await().untilAsserted(() -> assertThat(callback.exception).isNull());
        assertThatThrownBy(() -> callback.response.body().string()).hasMessageEndingWith("foo");
    }
}
