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

package com.linecorp.armeria.client.logging;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.metric.ClientRequestLifecycleListener;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogListener;
import com.linecorp.armeria.common.logging.RequestLogProperty;

/**
 * A default implementation of {@link RequestLogListener} that delegates {@link RequestLog} events
 * to a {@link ClientRequestLifecycleListener}.
 *
 * <p>This listener monitors the progress of a {@link RequestLog} and invokes the corresponding
 * callback methods of the {@link ClientRequestLifecycleListener} when specific {@link RequestLogProperty}
 * become available.
 */
public class DefaultClientRequestLogListener implements RequestLogListener {

    private final ClientRequestLifecycleListener lifecycleListener;

    /**
     * Creates a new instance with the specified {@link ClientRequestLifecycleListener}.
     *
     * @param lifecycleListener the listener to which the {@link RequestLog} events will be delegated
     */
    public DefaultClientRequestLogListener(ClientRequestLifecycleListener lifecycleListener) {
        this.lifecycleListener = lifecycleListener;
    }

    @Override
    public void onEvent(RequestLogProperty property, RequestLog log) {
        if (!(log.context() instanceof ClientRequestContext)) {
            return;
        }

        final ClientRequestContext ctx = (ClientRequestContext) log.context();
        switch (property) {
            case REQUEST_FIRST_BYTES_TRANSFERRED_TIME:
                lifecycleListener.onRequestStart(ctx);
                break;
            case REQUEST_COMPLETE:
                lifecycleListener.onRequestSendComplete(ctx);
                break;
            case RESPONSE_HEADERS:
                lifecycleListener.onResponseHeaders(ctx, log.responseHeaders());
                break;
            case RESPONSE_COMPLETE:
                lifecycleListener.onResponseComplete(ctx);
                break;
            case ALL_COMPLETE:
                lifecycleListener.onRequestComplete(ctx, log.responseCause());
                break;
            default:
                break;
        }
    }
}
