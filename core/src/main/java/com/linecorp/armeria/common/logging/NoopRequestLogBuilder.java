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

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

final class NoopRequestLogBuilder implements RequestLogBuilder {
    @Override
    public void startRequest(
            Channel ch, SessionProtocol sessionProtocol, String host, String method, String path) {}

    @Override
    public void serializationFormat(SerializationFormat serializationFormat) {}

    @Override
    public void increaseRequestLength(long deltaBytes) {}

    @Override
    public void requestLength(long requestLength) {}

    @Override
    public void requestEnvelope(Object requestEnvelope) {}

    @Override
    public void requestContent(Object requestContent) {}

    @Override
    public void deferRequestContent() {}

    @Override
    public void endRequest() {}

    @Override
    public void endRequest(Throwable requestCause) {}

    @Override
    public void startResponse() {}

    @Override
    public void statusCode(int statusCode) {}

    @Override
    public void increaseResponseLength(long deltaBytes) {}

    @Override
    public void responseLength(long responseLength) {}

    @Override
    public void responseEnvelope(Object responseEnvelope) {}

    @Override
    public void responseContent(Object responseContent) {}

    @Override
    public void deferResponseContent() {}

    @Override
    public void endResponse() {}

    @Override
    public void endResponse(Throwable responseCause) {}
}
