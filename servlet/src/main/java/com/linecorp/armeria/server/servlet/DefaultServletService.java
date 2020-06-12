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

import javax.servlet.http.HttpServlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link HttpService} which handles {@link HttpRequest} and forward to Servlet APIs,
 * and write {@link HttpResponse} to client.
 */
final class DefaultServletService implements HttpService {
    private static final Logger logger = LoggerFactory.getLogger(DefaultServletService.class);

    private final DefaultServletContext servletContext;

    /**
     * A class which helps a {@link DefaultServletService} have a {@link HttpServlet}.
     */
    DefaultServletService(DefaultServletContext servletContext) {
        requireNonNull(servletContext, "servletContext");
        this.servletContext = servletContext;
    }

    /**
     * handles {@link HttpRequest} and forward to Servlet APIs.
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        requireNonNull(ctx, "ctx");
        requireNonNull(req, "req");
        final HttpResponseWriter res = HttpResponse.streaming();
        req.aggregate().handleAsync((aReq, cause) -> {
            if (cause != null) {
                logger.warn("{} Failed to aggregate a request:", ctx, cause);
                if (res.tryWrite(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR))) {
                    res.close();
                }
                return null;
            }
            process(ctx, res, aReq);
            return null;
        }, ctx.blockingTaskExecutor());
        return res;
    }

    private void process(ServiceRequestContext ctx, HttpResponseWriter res, AggregatedHttpRequest req) {
        requireNonNull(res, "res");
        try {
            final ServletRequestDispatcher dispatcher = servletContext.getRequestDispatcher(ctx.path());
            if (dispatcher == null) {
                if (res.tryWrite(ResponseHeaders.of(HttpStatus.NOT_FOUND))) {
                    res.close();
                }
                return;
            }
            dispatcher.dispatch(new DefaultServletHttpRequest(ctx, servletContext, req),
                                new DefaultServletHttpResponse(servletContext, res));
        } catch (Throwable throwable) {
            logger.error("Servlet process failed: ", throwable);
        } finally {
            res.close();
        }
    }
}
