/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

final class AggregatedHttpRequestHandler extends AbstractHttpRequestHandler
        implements BiFunction<AggregatedHttpRequest, Throwable, Void> {

    private boolean cancelled;

    AggregatedHttpRequestHandler(Channel ch, ClientHttpObjectEncoder encoder,
                                 HttpResponseDecoder responseDecoder,
                                 HttpRequest request, DecodedHttpResponse originalRes,
                                 ClientRequestContext ctx, long timeoutMillis) {
        super(ch, encoder, responseDecoder, originalRes, ctx, timeoutMillis, request.isEmpty(), true, true);
    }

    @Override
    public Void apply(@Nullable AggregatedHttpRequest request, @Nullable Throwable throwable) {
        final EventLoop eventLoop = channel().eventLoop();
        if (eventLoop.inEventLoop()) {
            apply0(request, throwable);
        } else {
            eventLoop.execute(() -> apply0(request, throwable));
        }
        return null;
    }

    private void apply0(@Nullable AggregatedHttpRequest request, @Nullable Throwable throwable) {
        if (throwable != null) {
            failRequest(throwable);
            return;
        }

        assert request != null;
        if (!tryInitialize()) {
            request.content().close();
            return;
        }

        writeHeaders(request.headers());
        if (cancelled) {
            request.content().close();
            // If the headers size exceeds the limit, the headers write fails immediately.
            return;
        }

        HttpData content = request.content();
        final boolean contentEmpty = content.isEmpty();
        final HttpHeaders trailers = request.trailers();
        final boolean trailersEmpty = trailers.isEmpty();
        if (!contentEmpty) {
            if (trailersEmpty) {
                content = content.withEndOfStream();
            }
            writeData(content);
        }
        if (!trailersEmpty) {
            writeTrailers(trailers);
        }
        channel().flush();
    }

    @Override
    void onWriteSuccess() {
        // Do nothing because the write operations of `aggregatedResponse` occur sequentially.
    }

    @Override
    void cancel() {
        cancelled = true;
    }
}
