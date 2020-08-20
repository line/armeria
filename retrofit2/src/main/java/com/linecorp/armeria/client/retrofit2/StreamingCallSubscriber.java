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

import java.io.IOException;
import java.util.concurrent.Executor;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpData;

import okhttp3.Callback;
import okhttp3.Request;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;

final class StreamingCallSubscriber extends AbstractSubscriber {

    private static final Buffer emptyBuffer = new Buffer();

    private final PipeBuffer pipeBuffer = new PipeBuffer();
    private boolean responseCalled;

    StreamingCallSubscriber(ArmeriaCall armeriaCall, Callback callback, Request request,
                            Executor callbackExecutor) {
        super(armeriaCall, request, callback, callbackExecutor);
    }

    @Override
    void onSubscribe0() {
        request(1);
    }

    @Override
    void onCancelled() {
        final IOException canceledException = newCancelledException();
        safeOnFailure(canceledException);
        pipeBuffer.close(canceledException);
    }

    @Override
    void onHttpHeaders() {
        request(1);
    }

    @Override
    void onHttpData(HttpData data) {
        if (!responseCalled) {
            safeOnResponse(Okio.buffer(new ForwardingSource(pipeBuffer.source()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    request(1);
                    return super.read(sink, byteCount);
                }

                @Override
                public void close() throws IOException {
                    cancel();
                    super.close();
                }
            }));
            responseCalled = true;
        }
        pipeBuffer.write(data.array(), 0, data.length());
    }

    @Override
    void onError0(IOException e) {
        safeOnFailure(e);
        pipeBuffer.close(e);
    }

    @Override
    void onComplete0() {
        if (!responseCalled) {
            safeOnResponse(emptyBuffer);
            responseCalled = true;
        }
        pipeBuffer.close(null);
    }
}
