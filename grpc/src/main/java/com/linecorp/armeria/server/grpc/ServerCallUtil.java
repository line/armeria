/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ForwardingServerCall;
import io.grpc.ServerCall;

final class ServerCallUtil {

    @Nullable
    private static MethodHandle delegateMH;

    static {
        try {
            delegateMH = MethodHandles.lookup().findVirtual(ForwardingServerCall.class, "delegate",
                                                            MethodType.methodType(ServerCall.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            delegateMH = null;
        }
    }

    @Nullable
    static ServiceRequestContext findRequestContext(ServerCall<?, ?> serverCall) {
        final AbstractServerCall<?, ?> armeriaServerCall = findArmeriaServerCall(serverCall);
        if (armeriaServerCall != null) {
            return armeriaServerCall.ctx();
        }

        return ServiceRequestContext.currentOrNull();
    }

    @Nullable
    static <I, O> AbstractServerCall<I, O> findArmeriaServerCall(ServerCall<I, O> serverCall) {
        if (delegateMH != null) {
            while (serverCall instanceof ForwardingServerCall) {
                try {
                    //noinspection unchecked
                    serverCall = (ServerCall<I, O>) delegateMH.invoke(serverCall);
                } catch (Throwable e) {
                    break;
                }
            }
        }
        if (serverCall instanceof AbstractServerCall) {
            return (AbstractServerCall<I, O>) serverCall;
        } else {
            return null;
        }
    }

    private ServerCallUtil() {}
}
