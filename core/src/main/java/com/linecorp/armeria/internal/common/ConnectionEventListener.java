/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common;

import static com.google.common.base.Preconditions.checkState;

import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

public interface ConnectionEventListener {

    ConnectionEventListener NOOP = new ConnectionEventListener() {};

    static void setToChannelAttr(SessionProtocol sessionProtocol,
                                 ConnectionPoolListener connectionPoolListener,
                                 Channel channel) {
        channel.attr(DefaultConnectionEventListener.LISTENER)
               .set(new DefaultConnectionEventListener(sessionProtocol, connectionPoolListener, channel));
    }

    static ConnectionEventListener get(Channel channel) {
        final ConnectionEventListener listener = channel.attr(DefaultConnectionEventListener.LISTENER).get();
        checkState(listener != null, "ConnectionEventListener not set for %s.", channel);
        return listener;
    }

    default void connectionOpened() {}

    default void connectionClosed() {}

    default void pingWrite(long id) {}

    default void pingAck(long id) {}

    default void closeHint(CloseHint closeHint) {}

    enum CloseHint {
        PING_TIMEOUT,
        CONNECTION_IDLE,
        MAX_CONNECTION_AGE,
        UNKNOWN,
    }
}
