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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

public final class PendingExceptionUtil {

    private static final Logger logger = LoggerFactory.getLogger(PendingExceptionUtil.class);

    private static final AttributeKey<Throwable> PENDING_EXCEPTION =
            AttributeKey.valueOf(PendingExceptionUtil.class, "PENDING_EXCEPTION");

    @Nullable
    public static Throwable getPendingException(ChannelHandlerContext ctx) {
       return getPendingException(ctx.channel());
    }

    @Nullable
    public static Throwable getPendingException(Channel channel) {
        if (channel.hasAttr(PENDING_EXCEPTION)) {
            return channel.attr(PENDING_EXCEPTION).get();
        }

        return null;
    }

    public static void setPendingException(ChannelHandlerContext ctx, Throwable cause) {
        setPendingException(ctx.channel(), cause);
    }

    public static void setPendingException(Channel channel, Throwable cause) {
        final Throwable previousCause = channel.attr(PENDING_EXCEPTION).setIfAbsent(cause);
        if (previousCause != null) {
            logger.warn("{} Unexpected suppressed exception:", channel, cause);
        }
    }

    private PendingExceptionUtil() {}
}
