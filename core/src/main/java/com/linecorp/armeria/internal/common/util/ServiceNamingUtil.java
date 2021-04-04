/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
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

    private ServiceNamingUtil() {}
}
