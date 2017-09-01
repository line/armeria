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
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

interface HttpSession {

    HttpSession INACTIVE = new HttpSession() {

        private final InboundTrafficController inboundTrafficController =
                new InboundTrafficController(null, 0, 0);

        @Override
        public SessionProtocol protocol() {
            return null;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public InboundTrafficController inboundTrafficController() {
            return inboundTrafficController;
        }

        @Override
        public boolean hasUnfinishedResponses() {
            return false;
        }

        @Override
        public boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res) {
            res.close(ClosedSessionException.get());
            return false;
        }

        @Override
        public void retryWithH1C() {
            throw new IllegalStateException();
        }

        @Override
        public void deactivate() {}
    };

    static HttpSession get(Channel ch) {
        final ChannelHandler lastHandler = ch.pipeline().last();
        if (lastHandler instanceof HttpSession) {
            return (HttpSession) lastHandler;
        }

        for (ChannelHandler h : ch.pipeline().toMap().values()) {
            if (h instanceof HttpSession) {
                return (HttpSession) h;
            }
        }

        return INACTIVE;
    }

    SessionProtocol protocol();

    boolean isActive();

    InboundTrafficController inboundTrafficController();

    boolean hasUnfinishedResponses();

    boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res);

    void retryWithH1C();

    void deactivate();
}
