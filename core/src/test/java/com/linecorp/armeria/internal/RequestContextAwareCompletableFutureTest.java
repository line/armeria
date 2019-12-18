/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

class RequestContextAwareCompletableFutureTest {

    @Test
    void contextAwareFuture() {
        final RequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        final CompletableFuture<Void> contextAwareFuture = ctx.makeContextAware(future);

        final List<RequestContext> ctxs = new ArrayList<>();
        final CompletableFuture<Void> handleFuture = contextAwareFuture.handle((unused1, unused2) -> {
            ctxs.add(RequestContext.current());
            return null;
        });
        handleFuture.join();
        handleFuture.handle((unused1, unused2) -> {
            ctxs.add(RequestContext.current());
            return null;
        });

        assertThat(ctxs).containsExactly(ctx, ctx);
    }

    @Test
    void makeContextAwareCompletableFutureWithDifferentContext() {
        final RequestContext context1 =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Void> originalFuture = CompletableFuture.completedFuture(null);
        final CompletableFuture<Void> future1 = context1.makeContextAware(originalFuture);

        final RequestContext context2 =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final CompletableFuture<Void> future2 = context2.makeContextAware(future1);

        assertThat(future2).isCompletedExceptionally();
        assertThatThrownBy(future2::join).hasCauseInstanceOf(IllegalStateException.class);
    }
}
