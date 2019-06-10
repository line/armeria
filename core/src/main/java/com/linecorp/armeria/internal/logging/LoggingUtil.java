/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.logging;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.channel.Channel;

/**
 * A utility class for logging.
 */
public final class LoggingUtil {

    /**
     * Returns a remote host from the specified {@link HttpHeaders} and {@link Channel}.
     */
    public static String remoteHost(HttpHeaders headers, Channel channel) {
        requireNonNull(headers, "headers");
        requireNonNull(channel, "channel");
        String host = headers.get(HttpHeaderNames.AUTHORITY);
        if (host == null) {
            host = ((InetSocketAddress) channel.remoteAddress()).getHostString();
        } else {
            final int colonIdx = host.lastIndexOf(':');
            if (colonIdx > 0) {
                host = host.substring(0, colonIdx);
            }
        }
        return host;
    }

    private LoggingUtil() {}
}
