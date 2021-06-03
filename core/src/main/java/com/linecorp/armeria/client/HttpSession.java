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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

interface HttpSession {

    /**
     * 2^29 - We could have used 2^30 but this should be large enough.
     */
    int MAX_NUM_REQUESTS_SENT = 536870912;

    HttpSession INACTIVE = new HttpSession() {
        @Nullable
        @Override
        public SessionProtocol protocol() {
            return null;
        }

        @Override
        public boolean canSendRequest() {
            return false;
        }

        @Override
        public InboundTrafficController inboundTrafficController() {
            return InboundTrafficController.disabled();
        }

        @Override
        public boolean hasUnfinishedResponses() {
            return false;
        }

        @Override
        public boolean incrementNumUnfinishedResponses() {
            return false;
        }

        @Override
        public void invoke(PooledChannel pooledChannel, ClientRequestContext ctx,
                           HttpRequest req, DecodedHttpResponseWriter res) {
            res.close(ClosedSessionException.get());
        }

        @Override
        public void retryWithH1C() {
            throw new IllegalStateException();
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void deactivate() {}

        @Override
        public int incrementAndGetNumRequestsSent() {
            return MAX_NUM_REQUESTS_SENT;
        }
    };

    static HttpSession get(Channel ch) {
        final ChannelHandler lastHandler = ch.pipeline().last();
        if (lastHandler instanceof HttpSession) {
            return (HttpSession) lastHandler;
        }
        return INACTIVE;
    }

    @Nullable
    SessionProtocol protocol();

    boolean canSendRequest();

    InboundTrafficController inboundTrafficController();

    boolean hasUnfinishedResponses();

    boolean incrementNumUnfinishedResponses();

    void invoke(PooledChannel pooledChannel, ClientRequestContext ctx,
                HttpRequest req, DecodedHttpResponseWriter res);

    void retryWithH1C();

    boolean isActive();

    void deactivate();

    int incrementAndGetNumRequestsSent();
}
