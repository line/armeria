/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.auth;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A default implementation of {@link HttpAuthService}.
 */
final class HttpAuthServiceImpl extends HttpAuthService {

    private static final Logger logger = LoggerFactory.getLogger(HttpAuthServiceImpl.class);

    private final List<? extends Authorizer<HttpRequest>> authorizers;

    HttpAuthServiceImpl(Service<HttpRequest, HttpResponse> delegate,
                        Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        super(delegate);
        this.authorizers = ImmutableList.copyOf(authorizers);
    }

    @Override
    protected CompletionStage<Boolean> authorize(HttpRequest req, ServiceRequestContext ctx) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(false);
        for (Authorizer<HttpRequest> authorizer : authorizers) {
            result = result.exceptionally(t -> {
                logger.warn("Unexpected exception during authorization:", t);
                return false;
            }).thenComposeAsync(previousResult -> {
                if (previousResult) {
                    return CompletableFuture.completedFuture(true);
                }
                return authorizer.authorize(ctx, req);
            }, ctx.contextAwareEventLoop());
        }
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(authorizers).toString();
    }
}
