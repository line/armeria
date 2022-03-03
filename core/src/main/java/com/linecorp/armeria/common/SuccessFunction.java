/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * A function that accepts {@link RequestContext} and {@link RequestLog} for checking if the response is
 * success.
 *
 */
@FunctionalInterface
public interface SuccessFunction {
    /**
     * Default success response classification function which checks
     * {@link RequestLog#responseCause()} is null, 100 &lt;= {@link HttpStatus} &lt; 400
     * and {@link RpcResponse#isCompletedExceptionally()} == {@code false}.
     */
    static SuccessFunction ofDefault() {
        return (ctx, log) -> {
            if (log.responseCause() != null) {
                return false;
            }

            final int statusCode = log.responseHeaders().status().code();
            if (statusCode < 100 || statusCode >= 400) {
                return false;
            }

            final Object responseContent = log.responseContent();
            if (responseContent instanceof RpcResponse) {
                return !((RpcResponse) responseContent).isCompletedExceptionally();
            }

            return true;
        };
    }

    /**
     * Return true if the response is success.
     *
     * @see LoggingService#serve(ServiceRequestContext, HttpRequest)
     * @see MetricCollectingService#serve(ServiceRequestContext, HttpRequest)
     */
    boolean isSuccess(RequestContext ctx, RequestLog log);
}
