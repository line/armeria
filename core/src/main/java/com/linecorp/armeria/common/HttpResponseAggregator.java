/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.common.HttpObjectAggregator;

import io.netty.buffer.ByteBufAllocator;

final class HttpResponseAggregator extends HttpObjectAggregator<AggregatedHttpResponse> {

    @Nullable
    private List<ResponseHeaders> informationals; // needs aggregation as well
    @Nullable
    private ResponseHeaders headers;
    private HttpHeaders trailers;

    private boolean receivedMessageHeaders;

    HttpResponseAggregator(CompletableFuture<AggregatedHttpResponse> future,
                           @Nullable ByteBufAllocator alloc) {
        super(future, alloc);
        trailers = HttpHeaders.of();
    }

    @Override
    protected void onHeaders(HttpHeaders headers) {
        if (!receivedMessageHeaders) {
            onInformationalOrMessageHeaders(headers);
        } else if (trailers.isEmpty()) {
            trailers = headers;
        } else {
            // Optionally, only one trailers can be present.
            // See https://datatracker.ietf.org/doc/html/rfc7540#section-8.1
        }
    }

    @Override
    protected void onData(HttpData data) {
        if (!trailers.isEmpty()) {
            data.close();
            // Data can't come after trailers.
            // See https://datatracker.ietf.org/doc/html/rfc7540#section-8.1
            return;
        }
        super.onData(data);
    }

    private void onInformationalOrMessageHeaders(HttpHeaders headers) {
        final String status = headers.get(HttpHeaderNames.STATUS);
        if (status != null && !status.isEmpty() && status.charAt(0) != '1') {
            // Message headers.
            assert this.headers == null;
            this.headers = (ResponseHeaders) headers;
            receivedMessageHeaders = true;
        } else {
            if (informationals == null) {
                informationals = new ArrayList<>(2);
                informationals.add((ResponseHeaders) headers);
            } else if (status != null) {
                // A new informational headers
                informationals.add((ResponseHeaders) headers);
            } else {
                // Append to the last informational headers
                final int lastIdx = informationals.size() - 1;
                informationals.set(lastIdx, informationals.get(lastIdx).withMutations(h -> h.add(headers)));
            }
        }
    }

    @Override
    protected AggregatedHttpResponse onSuccess(HttpData content) {
        checkState(headers != null, "An aggregated message does not have headers.");
        return AggregatedHttpResponse.of(firstNonNull(informationals, Collections.emptyList()),
                                         headers, content, trailers);
    }

    @Override
    protected void onFailure() {
        headers = null;
        trailers = HttpHeaders.of();
    }
}
