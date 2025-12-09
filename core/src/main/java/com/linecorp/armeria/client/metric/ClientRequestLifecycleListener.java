package com.linecorp.armeria.client.metric;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

@UnstableApi
public interface ClientRequestLifecycleListener {

    /**
     * Called when a request is created and scheduled but not yet executed.
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
    
    static ClientRequestMetrics counting() {
        return new ClientRequestMetrics();
    }
    
}
