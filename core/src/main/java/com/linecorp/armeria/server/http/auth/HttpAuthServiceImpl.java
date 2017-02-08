/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.http.auth;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.internal.futures.CompletableFutures;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A default implementation of {@link HttpAuthService}.
 */
final class HttpAuthServiceImpl extends HttpAuthService {

    private final List<? extends Authorizer<HttpRequest>> authorizers;

    HttpAuthServiceImpl(Service<? super HttpRequest, ? extends HttpResponse> delegate,
                        Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        super(delegate);
        this.authorizers = ImmutableList.copyOf(authorizers);
    }

    @Override
    public CompletionStage<Boolean> authorize(HttpRequest req, ServiceRequestContext ctx) {
        CompletableFuture<List<Boolean>> results =
                CompletableFutures.allAsList(
                        authorizers.stream()
                                   .map(a -> a.authorize(ctx, req))
                                   .collect(toImmutableList()));
        return results.thenApply(r -> r.stream().anyMatch(s -> s));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(authorizers).toString();
    }
}
