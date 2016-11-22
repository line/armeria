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

package com.linecorp.armeria.common.logging;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.TextFormatter;

import io.netty.util.Attribute;

/**
 * Default {@link ResponseLog} implementation.
 */
public final class DefaultResponseLog
        extends AbstractMessageLog<ResponseLog> implements ResponseLog, ResponseLogBuilder {

    private final DefaultRequestLog request;
    private final CompletableFuture<?> requestLogFuture;
    private int statusCode;

    /**
     * Creates a new instance.
     *
     * @param request the {@link DefaultRequestLog} of the corresponding request
     */
    public DefaultResponseLog(DefaultRequestLog request, CompletableFuture<?> requestLogFuture) {
        this.request = request;
        this.requestLogFuture = requestLogFuture;
    }

    @Override
    public void start() {
        start0();
    }

    @Override
    public RequestLog request() {
        return request;
    }

    @Override
    public long responseTimeNanos() {
        return endTimeNanos() - request.startTimeNanos();
    }

    @Override
    public void statusCode(int statusCode) {
        if (isDone()) {
            return;
        }

        this.statusCode = statusCode;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    protected void appendProperties(StringBuilder buf) {
        buf.append(", responseTime=");
        TextFormatter.appendElapsed(buf, responseTimeNanos());
        buf.append(", statusCode=").append(statusCode);
    }

    @Override
    CompletableFuture<?> parentLogFuture() {
        return requestLogFuture;
    }

    @Override
    boolean includeAttr(Attribute<?> attr) {
        return attr.key() != RAW_RPC_RESPONSE;
    }
}
