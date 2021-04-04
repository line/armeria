package com.linecorp.armeria.internal.common.util;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.server.HttpService;

public final class ServiceNamingUtil {

    private static final String GRPC_SERVICE_NAME = "com.linecorp.armeria.internal.common.grpc.GrpcLogUtil";

    public static String fullTypeRpcServiceName(RpcRequest rpcReq) {
        final String serviceType = rpcReq.serviceType().getName();
        if (GRPC_SERVICE_NAME.equals(serviceType)) {
            // Parse gRPC serviceName and methodName
            final String fullMethodName = rpcReq.method();
            return fullMethodName.substring(0, fullMethodName.lastIndexOf('/'));
        } else {
            return serviceType;
        }
    }

    public static String fullTypeHttpServiceName(HttpService service) {
        Unwrappable unwrappable = service;
        for (;;) {
            final Unwrappable delegate = unwrappable.unwrap();
            if (delegate != unwrappable) {
                unwrappable = delegate;
                continue;
            }
            return delegate.getClass().getName();
        }
    }
}
