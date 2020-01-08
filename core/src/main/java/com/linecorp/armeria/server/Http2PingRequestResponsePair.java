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

package com.linecorp.armeria.server;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;

import io.netty.handler.codec.http2.Http2PingFrame;

/**
 * This class represents a pair of PING request and response.
 * If a request is sent but response is not received then this is not complete.
 * If a response is received but ping payload does not match request payload then this is complete with error.
 */
class Http2PingRequestResponsePair {
    @Nullable
    private Http2PingFrame requestFrame;
    @Nullable
    private Http2PingFrame responseFrame;

    public void setRequestFrame(final Http2PingFrame requestFrame) {
        Preconditions.checkNotNull(requestFrame, "requestFrame");
        Preconditions.checkArgument(!requestFrame.ack(), "requestFrame should not have ACK set");
        Preconditions.checkState(responseFrame != null, "Pending response for previous request");

        this.requestFrame = requestFrame;
    }

    public void setResponseFrame(final Http2PingFrame responseFrame) {
        Preconditions.checkNotNull(responseFrame, "responseFrame");
        Preconditions.checkArgument(responseFrame.ack(), "response should have ACK flag set");
        Preconditions.checkState(requestFrame != null, "ACK received before request sent");
        this.responseFrame = responseFrame;

        if (responseFrame.content() == requestFrame.content()) {
            throw new RuntimeException("asd");
        }
        reset();
    }

    private void reset() {
        requestFrame = null;
        responseFrame = null;
    }
}
