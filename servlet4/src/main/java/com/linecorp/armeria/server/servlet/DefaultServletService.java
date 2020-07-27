/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.servlet;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

final class DefaultServletService implements HttpService {
    private static final Logger logger = LoggerFactory.getLogger(DefaultServletService.class);

    private final DefaultServletContext servletContext;

    DefaultServletService(DefaultServletContext servletContext) {
        requireNonNull(servletContext, "servletContext");
        this.servletContext = servletContext;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        req.aggregate().handle((aReq, cause) -> {
            if (cause != null) {
                logger.warn("{} Failed to aggregate a request:", ctx, cause);
                future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
                return null;
            }
            process(ctx, aReq, future);
            return null;
        });
        return HttpResponse.from(future);
    }

    private void process(ServiceRequestContext ctx, AggregatedHttpRequest req,
                         CompletableFuture<HttpResponse> resFuture) {
        final String contextPath = servletContext.getContextPath();
        final String rest = ctx.path().substring(contextPath.length());
        final ServletRequestDispatcher dispatcher = servletContext.getRequestDispatcher(rest);
        if (dispatcher == null) {
            resFuture.complete(HttpResponse.of(HttpStatus.NOT_FOUND));
            return;
        }
        ctx.blockingTaskExecutor().execute(() -> {
            try {
                dispatcher.dispatch(new DefaultServletHttpRequest(
                        servletContext, ctx, req, dispatcher.servletPath(), dispatcher.pathInfo()),
                                    new DefaultServletHttpResponse(servletContext, resFuture));
            } catch (Throwable t) {
                resFuture.complete(HttpResponse.ofFailure(t));
            }
        });
    }
}
