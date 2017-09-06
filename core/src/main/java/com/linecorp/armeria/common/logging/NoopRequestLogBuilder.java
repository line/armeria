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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

final class NoopRequestLogBuilder implements RequestLogBuilder {

    @Override
    public void startRequest(Channel ch, SessionProtocol sessionProtocol, String host) {}

    @Override
    public void serializationFormat(SerializationFormat serializationFormat) {}

    @Override
    public void increaseRequestLength(long deltaBytes) {}

    @Override
    public void requestLength(long requestLength) {}

    @Override
    public void requestHeaders(HttpHeaders requestHeaders) {}

    @Override
    public void requestContent(Object requestContent, Object rawRequestContent) {}

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
    public void startResponse() {}

    @Override
    public void increaseResponseLength(long deltaBytes) {}

    @Override
    public void responseLength(long responseLength) {}

    @Override
    public void responseHeaders(HttpHeaders responseHeaders) {}

    @Override
    public void responseContent(Object responseContent, Object rawResponseContent) {}

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
}
