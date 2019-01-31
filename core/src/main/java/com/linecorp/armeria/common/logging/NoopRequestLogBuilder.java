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

package com.linecorp.armeria.common.logging;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

final class NoopRequestLogBuilder implements RequestLogBuilder {

    @Override
    public void addChild(RequestLog child) {}

    @Override
    public void endResponseWithLastChild() {}

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol) {}

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol,
                             long requestStartTimeNanos, long requestStartTimeMicros) {}

    @Override
    public void startRequest(Channel ch, SessionProtocol sessionProtocol, @Nullable SSLSession sslSession) {}

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol, @Nullable SSLSession sslSession,
                             long requestStartTimeNanos, long requestStartTimeMicros) {}

    @Override
    public void serializationFormat(SerializationFormat serializationFormat) {}

    @Override
    public void increaseRequestLength(long deltaBytes) {}

    @Override
    public void requestLength(long requestLength) {}

    @Override
    public void requestFirstBytesTransferred() {}

    @Override
    public void requestFirstBytesTransferred(long requestFirstBytesTransferredNanos) {}

    @Override
    public void requestHeaders(HttpHeaders requestHeaders) {}

    @Override
    public void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent) {}

    @Override
    public void deferRequestContent() {}

    @Override
    public boolean isRequestContentDeferred() {
        return false;
    }

    @Override
    public void endRequest() {}

    @Override
    public void endRequest(Throwable requestCause) {}

    @Override
    public void endRequest(long requestEndTimeNanos) {}

    @Override
    public void endRequest(Throwable requestCause, long requestEndTimeNanos) {}

    @Override
    public void startResponse() {}

    @Override
    public void startResponse(long responseStartTimeNanos, long responseStartTimeMicros) {}

    @Override
    public void increaseResponseLength(long deltaBytes) {}

    @Override
    public void responseLength(long responseLength) {}

    @Override
    public void responseFirstBytesTransferred() {}

    @Override
    public void responseFirstBytesTransferred(long responseFirstBytesTransferredNanos) {}

    @Override
    public void responseHeaders(HttpHeaders responseHeaders) {}

    @Override
    public void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent) {}

    @Override
    public void deferResponseContent() {}

    @Override
    public boolean isResponseContentDeferred() {
        return false;
    }

    @Override
    public void endResponse() {}

    @Override
    public void endResponse(Throwable responseCause) {}

    @Override
    public void endResponse(long responseEndTimeNanos) {}

    @Override
    public void endResponse(Throwable responseCause, long responseEndTimeNanos) {}
}
