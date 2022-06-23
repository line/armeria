package com.linecorp.armeria.server.grpc.validation;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public class RequestValidationInterceptor implements ServerInterceptor {

    private RequestValidatorResolver requestValidatorResolver;

    public RequestValidationInterceptor(RequestValidatorResolver requestValidatorResolver) {
        this.requestValidatorResolver = requestValidatorResolver;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);

        return new RequestValidationListener<>(delegate, call, headers, requestValidatorResolver);
    }
}
