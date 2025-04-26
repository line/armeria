package com.linecorp.armeria.server.jsonrpc;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;

public class JsonRpcExceptionHandler implements ExceptionHandlerFunction {

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        if (cause instanceof IllegalArgumentException){
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }

        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                MediaType.PLAIN_TEXT_UTF_8,
                cause.getMessage() != null ? cause.getMessage() : "Unknown error");
    }
}
