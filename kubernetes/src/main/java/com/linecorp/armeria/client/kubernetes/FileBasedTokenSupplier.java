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
package com.linecorp.armeria.client.kubernetes;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

public class FileBasedTokenSupplier implements DecoratingHttpClientFunction {

    private static final ExecutorService SINGLE_THREAD_EXECUTOR = Executors.newSingleThreadExecutor(
            ThreadFactories.newThreadFactory("armeria-k8s-token-refresh-executor", true));

    private final String file;
    private Instant expiry;

    @Nullable
    private volatile AuthToken token;

    public FileBasedTokenSupplier(String file) {
        expiry = Instant.MIN;
        this.file = file;
    }

    @Override
    public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req)
            throws Exception {
        return HttpResponse.from(fetchToken().thenApply(authToken -> {
            final HttpRequest newReq = req.mapHeaders(headers -> {
                return headers.toBuilder()
                              .set(HttpHeaderNames.AUTHORIZATION, authToken.asHeaderValue())
                              .build();
            });
            ctx.updateRequest(newReq);

            try {
                return delegate.execute(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    private CompletableFuture<AuthToken> fetchToken() {
        if (!Instant.now().isAfter(expiry)) {
            final AuthToken token = this.token;
            assert token != null;
            return UnmodifiableFuture.completedFuture(token);
        }

        return CompletableFuture.supplyAsync(() -> {
            if (!Instant.now().isAfter(expiry)) {
                // The token was updated by the task scheduled by another thread.
                final AuthToken token = this.token;
                assert token != null;
                return token;
            }

            try {
                final String tokenString = new String(Files.readAllBytes(Paths.get(file)),
                                                      Charset.defaultCharset()).trim();
                final AuthToken token = AuthToken.ofOAuth2(tokenString);
                this.token = token;
                expiry = Instant.now().plusSeconds(60);
                return token;
            } catch (IOException ie) {
                throw new IllegalStateException("Cannot read file: " + file, ie);
            }
        }, SINGLE_THREAD_EXECUTOR);
    }
}
