/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.hessian;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.CompletableRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.hessian.HessianFaultException;
import com.linecorp.armeria.internal.common.hessian.HessianMethod;
import com.linecorp.armeria.internal.common.hessian.HessianMethod.ResponseType;
import com.linecorp.armeria.internal.common.hessian.HessianNoSuchMethodException;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link RpcService} that handles a Hessian {@link RpcRequest}.
 *
 * @see HessianHttpServiceImpl
 */
public final class HessianCallService implements RpcService {

    private static final Logger logger = LoggerFactory.getLogger(HessianCallService.class);

    private final Map<String, HessianServiceMetadata> hessianServices;

    /**
     * create {@code HessianCallService}. key is path, value is metadata.
     */
    public static HessianCallService of(Map<String, HessianServiceMetadata> implementations) {
        requireNonNull(implementations, "implementations");
        return new HessianCallService(implementations);
    }

    private HessianCallService(Map<String, HessianServiceMetadata> implementations) {
        requireNonNull(implementations, "implementations");
        if (implementations.isEmpty()) {
            throw new IllegalArgumentException("empty implementations");
        }
        hessianServices = implementations;
    }

    @Nullable
    public HessianServiceMetadata hessianServiceMetadataOfPath(String path) {
        return hessianServices.get(path);
    }

    public Map<String, HessianServiceMetadata> getHessianServices() {
        return hessianServices;
    }

    @Override
    public RpcResponse serve(ServiceRequestContext ctx, RpcRequest call) throws Exception {
        if (!(call instanceof HessianRpcRequest)) {
            logger.error("call must be HessianRpcRequest, got {}", call);
            return RpcResponse.ofFailure(new HessianFaultException(
                    "call must be HessianRpcRequest, got" + call.getClass(), "InternalError", null));
        }
        @Nullable
        final HessianServiceMetadata metadata = ((HessianRpcRequest) call).getMetadata();

        final String method = call.method();

        // Ensure that such a service exists.
        if (metadata != null) {
            // Ensure that such a method exists.
            @Nullable
            final HessianMethod f = metadata.method(method);
            if (f != null) {
                if (f.getImplementation() != null) {
                    final CompletableRpcResponse reply = new CompletableRpcResponse();
                    invoke(ctx, f.getImplementation(), f, call.params().toArray(), reply);
                    return reply;
                }
                // Should never reach here.
                return RpcResponse.ofFailure(
                        new HessianFaultException("unknown method: " + call.method(), "NoSuchMethodException",
                                                  null));
            }
        }

        if ("_hessian_getAttribute".equals(method)) {
            return handleAttributeRequest(call, call.params().get(0));
        }
        return RpcResponse.ofFailure(
                new HessianFaultException("unknown method: " + call.method(), "NoSuchMethodException", null));
    }

    private RpcResponse handleAttributeRequest(RpcRequest call, Object attrName) {
        assert attrName instanceof CharSequence;
        final CharSequence name = (CharSequence) attrName;
        final String apiCLass = call.serviceType().getName();
        //
        if ("java.api.class".contentEquals(name) ||
            "java.home.class".contentEquals(name) ||
            "java.object.class".contentEquals(name)
        ) {
            return RpcResponse.of(apiCLass);
        }
        return RpcResponse.of(
                new HessianNoSuchMethodException("not support attrName: " + attrName, "NoSuchMethod"));
    }

    private static void invoke(ServiceRequestContext ctx, Object impl, HessianMethod func, Object[] args,
                               CompletableRpcResponse reply) {

        try {
            doInvoke(ctx, impl, func, args, reply);
        } catch (Throwable t) {
            reply.completeExceptionally(t);
        }
    }

    private static void doInvoke(ServiceRequestContext ctx, Object impl, HessianMethod func, Object[] args,
                                 CompletableRpcResponse reply) {

        if (func.isBlocking()) {
            ctx.blockingTaskExecutor().execute(() -> {
                doCall(ctx, impl, func, args, reply);
            });
        } else {
            doCall(ctx, impl, func, args, reply);
        }
    }

    private static void doCall(ServiceRequestContext ctx, Object impl, HessianMethod func, Object[] args,
                               CompletableRpcResponse reply) {

        if (reply.isDone()) {
            // Closed already most likely due to timeout.
            return;
        }

        try {
            final Object result = func.getMethod().invoke(impl, args);
            if (func.getResponseType() == ResponseType.COMPLETION_STAGE) {
                completeAsync(result, reply);
            } else {
                reply.complete(result);
            }
        } catch (Throwable t) {
            reply.completeExceptionally(t);
        }
    }

    private static void completeAsync(Object resultFuture, CompletableRpcResponse reply) {
        if (resultFuture instanceof CompletionStage) {
            final CompletionStage<?> future = (CompletionStage<?>) resultFuture;
            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    reply.completeExceptionally(throwable);
                } else {
                    reply.complete(result);
                }
            });
        } else {
            logger.error("Not support async Hessian service return type: {}", resultFuture.getClass());
            reply.completeExceptionally(new HessianFaultException(
                    "Not support async Hessian service return type:" + resultFuture.getClass(),
                    "MethodNotSupport",
                    null));
        }
    }
}
