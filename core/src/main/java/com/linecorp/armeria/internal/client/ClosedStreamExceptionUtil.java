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

import static com.linecorp.armeria.internal.client.PendingExceptionUtil.getPendingException;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.stream.ClosedStreamException;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

public final class ClosedStreamExceptionUtil {

    public static ClosedStreamException newClosedStreamException(ChannelHandlerContext ctx) {
        return newClosedStreamException(ctx.channel());
    }

    public static ClosedStreamException newClosedStreamException(Channel channel) {
        final Throwable pendingException = getPendingException(channel);
        if (pendingException == null) {
            return ClosedStreamException.get();
        } else if (pendingException instanceof ClosedStreamException) {
            return (ClosedStreamException) pendingException;
        } else  {
            return new ClosedStreamException(pendingException);
        }
    }

    public static ClosedSessionException newClosedSessionException(ChannelHandlerContext ctx) {
        return newClosedSessionException(ctx.channel());
    }

    public static ClosedSessionException newClosedSessionException(Channel channel) {
        final Throwable pendingException = getPendingException(channel);
        if (pendingException == null) {
            return ClosedSessionException.get();
        } else if (pendingException instanceof ClosedSessionException) {
            return (ClosedSessionException) pendingException;
        } else  {
            return new ClosedSessionException(pendingException);
        }
    }

    private ClosedStreamExceptionUtil() {}
}
