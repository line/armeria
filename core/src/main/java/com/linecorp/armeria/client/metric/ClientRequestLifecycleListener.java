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

package com.linecorp.armeria.client.metric;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.logging.DefaultClientRequestLogListener;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLogListener;

/**
 * Listens to the lifecycle events of client requests.
 *
 * <p>This interface provides a high-level view of a request's lifecycle (e.g., pending, started, completed).
 * unlike {@link RequestLogListener} which provides low-level property changes.
 *
 * <p>Note: The methods in this interface are typically not invoked directly by the
 * transport layer. Instead, they are triggered by inspecting the changes in
 * {@link com.linecorp.armeria.common.logging.RequestLog}.
 * Implementations should use {@link #asRequestLogListener()} to bridge
 * {@link com.linecorp.armeria.common.logging.RequestLogProperty}
 * changes to these lifecycle methods.
 *
 * <p>For example, a standard implementation might map:
 * <ul>
 * <li>{@link com.linecorp.armeria.common.logging.RequestLogProperty#REQUEST_FIRST_BYTES_TRANSFERRED_TIME}
 * to {@link #onRequestStart(ClientRequestContext)}</li>
 * <li>{@link com.linecorp.armeria.common.logging.RequestLogProperty#ALL_COMPLETE}
 * to {@link #onRequestComplete(ClientRequestContext, Throwable)}</li>
 * </ul>
 *
 * @see com.linecorp.armeria.client.ClientFactoryBuilder#clientRequestLifecycleListener(
 * ClientRequestLifecycleListener)
 */
@UnstableApi
public interface ClientRequestLifecycleListener {

    /**
     * Invoked when a request is created and scheduled for execution but has not yet started.
     * Note: This method is explicitly invoked by HttpClientDelegate when
     * HttpClientDelegate starts to call execute().
     */
    void onRequestPending(ClientRequestContext ctx);

    /**
     * Called when the client begins execution (connection acquired, headers sent).
     */
    void onRequestStart(ClientRequestContext ctx);

    /**
     * Called when the request is fully sent.
     */
    void onRequestSendComplete(ClientRequestContext ctx);

    /**
     * Called when the first response headers are received.
     */
    void onResponseHeaders(ClientRequestContext ctx, ResponseHeaders headers);

    /**
     * Called when the full response body is received successfully.
     */
    void onResponseComplete(ClientRequestContext ctx);

    /**
     * Called when a request is completed with either success or failure.
     */
    void onRequestComplete(ClientRequestContext ctx, @Nullable Throwable cause);

    /**
     * Returns a {@link RequestLogListener} that delegates
     * {@link com.linecorp.armeria.common.logging.RequestLog}
     * events to this lifecycle listener.
     * This method bridges the low-level {@link com.linecorp.armeria.common.logging.RequestLog}
     * updates to the high-level lifecycle methods
     * defined in this interface. The returned listener is registered to the {@link ClientRequestContext}
     * automatically when the request starts.
     */
    default RequestLogListener asRequestLogListener() {
        return new DefaultClientRequestLogListener(this);
    }

    /**
     * Returns a {@link ClientRequestMetrics} that collects the number of pending and active requests.
     */
    static ClientRequestMetrics counting() {
        return new ClientRequestMetrics();
    }

    /**
     * Returns a {@link ClientRequestLifecycleListener} that does nothing.
     */
    static ClientRequestLifecycleListener noop() {
        return NoopClientRequestLifecycleListener.getInstance();
    }
}
