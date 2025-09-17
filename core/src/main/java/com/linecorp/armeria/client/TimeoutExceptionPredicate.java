/*
 * Copyright 2025 LINE Corporation
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

import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.dns.DnsUtil;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.proxy.ProxyConnectException;

final class TimeoutExceptionPredicate {

    static boolean isTimeoutException(@Nullable Throwable cause) {
        if (cause == null) {
            return false;
        }
        cause = peel(cause);

        if (cause instanceof TimeoutException) {
            // Armeria timeout.
            return true;
        }
        if (cause instanceof ConnectTimeoutException) {
            // A connection level timeout.
            return true;
        }

        if (cause instanceof ProxyConnectException) {
            // https://github.com/netty/netty/blob/0138f2335593d50e3eb1d8e0e97d3e8438f7a74d/handler-proxy/src/main/java/io/netty/handler/proxy/ProxyHandler.java#L201
            final String message = cause.getMessage();
            // TODO(ikhoon): Consider sending a PR to Netty to add a dedicated exception type for this.
            if (message != null && message.contains("timeout")) {
                return true;
            }
        }
        // A DNS level timeout.
        return DnsUtil.isDnsQueryTimedOut(cause);
    }

    private static Throwable peel(Throwable cause) {
        cause = Exceptions.peel(cause);
        if (cause instanceof UnprocessedRequestException) {
            cause = cause.getCause();
        }
        assert cause != null;
        return cause;
    }

    private TimeoutExceptionPredicate() {}
}
