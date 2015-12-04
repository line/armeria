/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/**
 * A {@link Service} that handles an HTTP request. This {@link Service} must run on a {@link ServerPort}
 * whose {@link SessionProtocol} is {@linkplain SessionProtocol#ofHttp() HTTP}.
 */
public class HttpService implements Service {

    static final HttpServiceCodec CODEC = new HttpServiceCodec();

    private final ServiceInvocationHandler handler;

    /**
     * Creates a new instance with the specified {@link ServiceInvocationHandler}.
     */
    public HttpService(ServiceInvocationHandler handler) {
        this.handler = requireNonNull(handler, "handler");
    }

    /**
     * Creates a new instance with {@link ServiceInvocationHandler} unspecified. Use this constructor and
     * override the {@link #handler()} method if you cannot instantiate your handler because it requires this
     * {@link Service} to be instantiated first.
     */
    public HttpService() {
        handler = null;
    }

    @Override
    public ServiceCodec codec() {
        return CODEC;
    }

    @Override
    public ServiceInvocationHandler handler() {
        final ServiceInvocationHandler handler = this.handler;
        if (handler == null) {
            throw new IllegalStateException(getClass().getName() + ".handler() not implemented");
        }
        return handler;
    }

    /**
     * Creates a new {@link HttpService} that tries this {@link HttpService} first and then the specified
     * {@link HttpService} when this {@link HttpService} returned a {@code 404 Not Found} response.
     *
     * @param nextService the {@link HttpService} to try secondly
     */
    public HttpService orElse(HttpService nextService) {
        requireNonNull(nextService, "nextService");
        return new HttpService(new OrElseHandler(handler(), nextService.handler()));
    }

    @Override
    public String toString() {
        return "HttpService(" + handler().getClass().getSimpleName() + ')';
    }

    private static final class OrElseHandler implements ServiceInvocationHandler {

        private final ServiceInvocationHandler first;
        private final ServiceInvocationHandler second;

        OrElseHandler(ServiceInvocationHandler first, ServiceInvocationHandler second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void invoke(ServiceInvocationContext ctx,
                           Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {

            invoke0(ctx, blockingTaskExecutor, first, second, promise);
        }

        private void invoke0(ServiceInvocationContext ctx, Executor blockingTaskExecutor,
                             ServiceInvocationHandler subHandler, ServiceInvocationHandler nextSubHandler,
                             Promise<Object> promise) throws Exception {

            Promise<Object> subPromise = ctx.eventLoop().newPromise();
            subHandler.invoke(ctx, blockingTaskExecutor, subPromise);
            if (subPromise.isDone()) {
                handleResponse(ctx, blockingTaskExecutor, subPromise, nextSubHandler, promise);
            } else {
                subPromise.addListener(
                        future -> handleResponse(ctx, blockingTaskExecutor, future, nextSubHandler, promise));
            }
        }

        void handleResponse(ServiceInvocationContext ctx, Executor blockingTaskExecutor,
                            Future<Object> subFuture, ServiceInvocationHandler nextSubHandler,
                            Promise<Object> promise) throws Exception {

            if (!subFuture.isSuccess()) {
                // sub-handler failed with an exception.
                promise.tryFailure(subFuture.cause());
                return;
            }

            final FullHttpResponse res = (FullHttpResponse) subFuture.getNow();
            if (res.status().code() == HttpResponseStatus.NOT_FOUND.code() && nextSubHandler != null) {
                // The current sub-handler returned 404. Try the next sub-handler.
                res.release();
                if (!promise.isDone()) {
                    invoke0(ctx, blockingTaskExecutor, nextSubHandler, null, promise);
                }
                return;
            }

            // The current sub-handler returned non-404 or it is the last resort.
            if (!promise.trySuccess(res)) {
                res.release();
            }
        }
    }
}
