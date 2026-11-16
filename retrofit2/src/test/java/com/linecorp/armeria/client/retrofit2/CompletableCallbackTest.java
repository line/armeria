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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import okhttp3.Call;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;

class CompletableCallbackTest {

    @Test
    void responseBeforeAbandonmentClosesUnclaimedBodyOnce() {
        final AtomicInteger closed = new AtomicInteger();
        final CompletableCallback callback = new CompletableCallback();
        callback.onResponse(mock(Call.class), response(closed));
        callback.abandon(new InterruptedIOException());
        assertThat(closed).hasValue(1);
    }

    @Test
    void responseAfterAbandonmentClosesUnclaimedBodyOnce() {
        final AtomicInteger closed = new AtomicInteger();
        final CompletableCallback callback = new CompletableCallback();
        callback.abandon(new InterruptedIOException());
        callback.onResponse(mock(Call.class), response(closed));
        assertThat(closed).hasValue(1);
    }

    @Test
    void successfulHandoffLeavesBodyWithCaller() throws Exception {
        final AtomicInteger closed = new AtomicInteger();
        final CompletableCallback callback = new CompletableCallback();
        callback.onResponse(mock(Call.class), response(closed));
        try (Response response = callback.get()) {
            assertThat(closed).hasValue(0);
            assertThat(response.body().string()).isEqualTo("body");
        }
        assertThat(closed).hasValue(1);
    }

    private static Response response(AtomicInteger closed) {
        return new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
                                     .protocol(Protocol.HTTP_1_1)
                                     .code(200)
                                     .message("OK")
                                     .body(ResponseBody.create(null, 4, Okio.buffer(new ForwardingSource(
                                             new Buffer().writeUtf8("body")) {
                                         @Override
                                         public void close() throws IOException {
                                             closed.incrementAndGet();
                                             super.close();
                                         }
                                     })))
                                     .build();
    }
}
