/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

class ArmeriaCallEncodingTest {

    private final ClientFactory factory = ClientFactory.builder().build();
    private final AtomicInteger dispatched = new AtomicInteger();
    private final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                              .factory(factory)
                                              .decorator((delegate, ctx, req) -> {
                                                  dispatched.incrementAndGet();
                                                  return HttpResponse.of(HttpStatus.OK);
                                              })
                                              .build();

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @ParameterizedTest
    @CsvSource({ "false, false, true", "false, true, true", "false, true, false",
                 "true, false, true", "true, true, true", "true, true, false" })
    void encoderInterruption(boolean asynchronous, boolean restoreFlag, boolean retainCause) throws Exception {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<IOException> encodingFailure = new CompletableFuture<>();
        final RequestBody body = new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                writing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("encoding did not receive an interrupt");
                    }
                } catch (InterruptedException e) {
                    if (restoreFlag) {
                        Thread.currentThread().interrupt();
                    }
                    final IOException cause;
                    if (retainCause) {
                        cause = new InterruptedIOException("encoding interrupted");
                        cause.initCause(new IOException("interrupted source", e));
                    } else {
                        cause = new IOException("encoding interrupted");
                    }
                    encodingFailure.complete(cause);
                    throw cause;
                }
            }
        };
        final Call call = newCall(body);
        final CompletableFuture<Throwable> result = new CompletableFuture<>();
        final CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        final Thread caller = new Thread(() -> {
            try {
                if (asynchronous) {
                    call.enqueue(mock(Callback.class));
                } else {
                    call.execute().close();
                }
                result.complete(null);
            } catch (Throwable cause) {
                result.complete(cause);
            } finally {
                interrupted.complete(Thread.currentThread().isInterrupted());
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            final Throwable cause = result.get(5, TimeUnit.SECONDS);
            final IOException original = encodingFailure.get(5, TimeUnit.SECONDS);
            if (asynchronous) {
                assertThat(cause).isInstanceOf(IllegalArgumentException.class).hasCause(original);
            } else {
                assertThat(cause).isInstanceOf(InterruptedIOException.class).hasCause(original);
            }
            assertThat(interrupted.get(5, TimeUnit.SECONDS)).isEqualTo(!asynchronous || restoreFlag);
            assertThat(call.isCanceled()).isEqualTo(!asynchronous);
            assertThat(call.isExecuted()).isTrue();
            assertThat(dispatched).hasValue(0);
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(5000);
        }
    }

    @ParameterizedTest
    @CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    void timeoutWithoutThreadInterruptionKeepsEncodingFailure(boolean asynchronous, boolean socketTimeout) {
        final IOException cause = socketTimeout ? new SocketTimeoutException("source timed out")
                                                : new InterruptedIOException("source timed out");
        final Call call = newCall(new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                throw cause;
            }
        });
        assertThatThrownBy(() -> {
            if (asynchronous) {
                call.enqueue(mock(Callback.class));
            } else {
                call.execute();
            }
        }).isInstanceOf(IllegalArgumentException.class).hasCause(cause);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(call.isCanceled()).isFalse();
        assertThat(call.isExecuted()).isTrue();
        assertThat(dispatched).hasValue(0);
    }

    @ParameterizedTest
    @Timeout(value = 5, threadMode = ThreadMode.SEPARATE_THREAD)
    @ValueSource(booleans = { false, true })
    void cyclicEncodingCauseDoesNotPreventFailure(boolean interruptedCause) {
        final IOException cause = new IOException("encoding failed");
        final Throwable nested = interruptedCause ? new InterruptedException("source interrupted")
                                                 : new IOException("source failed");
        cause.initCause(nested);
        nested.initCause(cause);
        final Call call = newCall(new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                throw cause;
            }
        });
        try {
            assertThatThrownBy(call::execute)
                    .isInstanceOf(interruptedCause ? InterruptedIOException.class
                                                   : IllegalArgumentException.class)
                    .hasCause(cause);
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interruptedCause);
            assertThat(call.isCanceled()).isEqualTo(interruptedCause);
            assertThat(call.isExecuted()).isTrue();
            assertThat(dispatched).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    private Call newCall(RequestBody body) {
        return ArmeriaRetrofit.builder(client).build().callFactory().newCall(
                new Request.Builder().url("http://127.0.0.1:1/").post(body).build());
    }
}
