/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

public interface HttpSession {

    /**
     * 2^29 - We could have used 2^30 but this should be large enough.
     */
    int MAX_NUM_REQUESTS_SENT = 536870912;

    HttpSession INACTIVE = new HttpSession() {

        @Override
        public SerializationFormat serializationFormat() {
            return SerializationFormat.UNKNOWN;
        }

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
                           HttpRequest req, DecodedHttpResponse res) {
            res.close(ClosedSessionException.get());
        }

        @Override
        public void retryWith(SessionProtocol protocol) {
            throw new IllegalStateException();
        }

        @Override
        public boolean isAcquirable() {
            return false;
        }

        @Override
        public boolean isAcquirable(KeepAliveHandler keepAliveHandler) {
            return false;
        }

        @Override
        public void markUnacquirable() {}

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

    SerializationFormat serializationFormat();

    /**
     * Returns the explicit {@link SessionProtocol} of this {@link HttpSession}.
     * This is one of {@link SessionProtocol#H1}, {@link SessionProtocol#H1C}, {@link SessionProtocol#H2} and
     * {@link SessionProtocol#H2C}.
     */
    @Nullable
    SessionProtocol protocol();

    /**
     * Returns whether this {@link HttpSession} is healthy. {@code true} if a new request can acquire this
     * session from {@code com.linecorp.armeria.client.HttpChannelPool}.
     */
    boolean isAcquirable();

    /**
     * Returns whether this {@link HttpSession} is healthy using the {@link KeepAliveHandler}.
     * {@code true} if a new request can acquire this session from
     * {@code com.linecorp.armeria.client.HttpChannelPool}. {@link KeepAliveHandler#needsDisconnection()}
     * is also used to determine whether this {@link HttpSession} is healthy.
     */
    boolean isAcquirable(KeepAliveHandler keepAliveHandler);

    /**
     * Deactivates this {@link HttpSession} to prevent new requests from acquiring this {@link HttpSession}.
     * This method may be invoked when:
     * <ul>
     *     <li>A connection is closed.</li>
     *     <li>"Connection: close" header is sent or received.</li>
     *     <li>A GOAWAY frame is sent or received.</li>
     *     <li>A {@link WriteTimeoutException} is raised</li>
     * </ul>
     */
    void markUnacquirable();

    /**
     * Returns {@code true} if a new request can be sent with this {@link HttpSession}.
     * Note that {@link #canSendRequest()} may return {@code true} even if {@link #isAcquirable()} is
     * {@code false} when the session is in the initial phase of a graceful shutdown.
     */
    boolean canSendRequest();

    InboundTrafficController inboundTrafficController();

    boolean hasUnfinishedResponses();

    boolean incrementNumUnfinishedResponses();

    void invoke(PooledChannel pooledChannel, ClientRequestContext ctx,
                HttpRequest req, DecodedHttpResponse res);

    void retryWith(SessionProtocol protocol);

    int incrementAndGetNumRequestsSent();
}
