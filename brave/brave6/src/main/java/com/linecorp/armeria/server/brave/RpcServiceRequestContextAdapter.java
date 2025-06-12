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

package com.linecorp.armeria.server.brave;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.brave.SpanContextUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.rpc.RpcServerHandler;
import brave.rpc.RpcServerRequest;
import brave.rpc.RpcServerResponse;

/**
 * Wraps {@link ServiceRequestContext} in an {@link brave.rpc.RpcServerRequest}, for use in
 * {@link RpcServerHandler}.
 */
final class RpcServiceRequestContextAdapter {

    static RpcServerRequest asRpcServerRequest(ServiceRequestContext ctx) {
        return new ArmeriaRpcServerRequest(ctx);
    }

    private static class ArmeriaRpcServerRequest extends RpcServerRequest {

        private final ServiceRequestContext ctx;

        ArmeriaRpcServerRequest(ServiceRequestContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public long startTimestamp() {
            return ctx.log().ensureAvailable(RequestLogProperty.REQUEST_START_TIME).requestStartTimeMicros();
        }

        @Nullable
        @Override
        public String method() {
            final RpcRequest rpcRequest = ctx.rpcRequest();
            if (rpcRequest == null) {
                return null;
            }
            return rpcRequest.method();
        }

        @Nullable
        @Override
        public String service() {
            final RpcRequest rpcRequest = ctx.rpcRequest();
            if (rpcRequest == null) {
                return null;
            }
            return rpcRequest.serviceName();
        }

        @Nullable
        @Override
        protected String propagationField(String keyName) {
            return ctx.request().headers().get(keyName);
        }

        @Override
        public Object unwrap() {
            return ctx;
        }
    }

    static RpcServerResponse asRpcServerResponse(ServiceRequestContext ctx, RequestLog log,
                                                 RpcServerRequest braveReq) {
        return new ArmeriaRpcServerResponse(ctx, log, braveReq);
    }

    private static class ArmeriaRpcServerResponse extends RpcServerResponse {

        private final ServiceRequestContext ctx;
        private final RequestLog log;
        private final RpcServerRequest braveReq;

        ArmeriaRpcServerResponse(ServiceRequestContext ctx, RequestLog log, RpcServerRequest braveReq) {
            this.ctx = ctx;
            this.log = log;
            this.braveReq = braveReq;
        }

        @Override
        public brave.rpc.RpcRequest request() {
            return braveReq;
        }

        @Nullable
        @Override
        public String errorCode() {
            return null;
        }

        @Nullable
        @Override
        public Throwable error() {
            return log.responseCause();
        }

        @Override
        public long finishTimestamp() {
            return SpanContextUtil.wallTimeMicros(log, log.responseEndTimeNanos());
        }

        @Override
        public Object unwrap() {
            return ctx;
        }
    }

    private RpcServiceRequestContextAdapter() {}
}
