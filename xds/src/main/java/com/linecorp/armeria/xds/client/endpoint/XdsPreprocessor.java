/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.XdsAttributeKeys.ROUTE_CONFIG;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.Preprocessor;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.netty.channel.EventLoop;

abstract class XdsPreprocessor<I extends Request, O extends Response>
        implements Preprocessor<I, O>, AutoCloseable {

    private final ListenerRoot listenerRoot;
    private final RouteConfigSelector routeConfigSelector;
    private final String listenerName;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientPreprocessors, PreClient<I, O>, PreClient<I, O>> filterFunction;

    XdsPreprocessor(String listenerName, XdsBootstrap xdsBootstrap,
                    Function<CompletableFuture<O>, O> futureConverter,
                    BiFunction<ClientPreprocessors, PreClient<I, O>, PreClient<I, O>> filterFunction) {
        this.listenerName = listenerName;
        this.futureConverter = futureConverter;
        this.filterFunction = filterFunction;
        listenerRoot = xdsBootstrap.listenerRoot(listenerName);
        routeConfigSelector = new RouteConfigSelector(listenerRoot);
    }

    @Override
    public O execute(PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception {
        final RouteConfig routeConfig = routeConfigSelector.selectNow(ctx);
        if (routeConfig != null) {
            return execute0(delegate, ctx, req, routeConfig);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<O> resFuture =
                routeConfigSelector.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                                   .thenApply(routeConfig0 -> {
                                       try {
                                           return execute0(delegate, ctx, req, routeConfig0);
                                       } catch (Exception e) {
                                           return Exceptions.throwUnsafely(e);
                                       }
                                   });
        return futureConverter.apply(resFuture);
    }

    private O execute0(PreClient<I, O> delegate, PreClientRequestContext ctx, I req,
                       @Nullable RouteConfig routeConfig) throws Exception {
        if (routeConfig == null) {
            throw UnprocessedRequestException.of(
                    new TimeoutException("Couldn't select a snapshot for listener '" + listenerName + "'."));
        }
        ctx.setAttr(ROUTE_CONFIG, routeConfig);
        return execute1(delegate, ctx, req, routeConfig);
    }

    abstract O execute1(PreClient<I, O> delegate, PreClientRequestContext ctx, I req,
                        RouteConfig routeConfig) throws Exception;

    @Override
    public void close() {
        listenerRoot.close();
    }
}
