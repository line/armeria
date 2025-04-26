package com.linecorp.armeria.server.jsonrpc;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;

public class JsonRpcRequestParseException extends RuntimeException {
    private static final long serialVersionUID = -5526383831125611610L;
    private final JsonRpcResponse errorResponse;
    @Nullable
    private final Object requestId;

    JsonRpcRequestParseException(Throwable cause, JsonRpcResponse errorResponse, @Nullable Object requestId) {
        super(cause.getMessage(), cause);
        this.errorResponse = errorResponse;
        this.requestId = requestId;
    }

    JsonRpcResponse getErrorResponse() {
        return errorResponse;
    }

    @Nullable
    Object getRequestId() {
        return requestId;
    }
}
