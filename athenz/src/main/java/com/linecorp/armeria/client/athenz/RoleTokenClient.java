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

package com.linecorp.armeria.client.athenz;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Joiner;
import com.yahoo.athenz.zts.RoleToken;

import com.linecorp.armeria.client.InvalidHttpResponseException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientRequestPreparation;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.athenz.AccessDeniedException;
import com.linecorp.armeria.common.util.AsyncLoader;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

public class RoleTokenClient implements TokenClient {

    static final Joiner ROLE_JOINER = Joiner.on(",");

    private final WebClient webClient;
    private final String domainName;
    private final String roleNames;
    private final long refreshBeforeSec;
    private final AsyncLoader<RoleToken> tokenLoader;

    RoleTokenClient(ZtsBaseClient ztsBaseClient, String domainName, List<String> roleNames,
                    Duration refreshBefore) {
        webClient = ztsBaseClient.webClient();
        this.domainName = domainName;
        this.roleNames = ROLE_JOINER.join(roleNames);
        refreshBeforeSec = refreshBefore.getSeconds();
        tokenLoader = AsyncLoader.<RoleToken>builder(unused -> fetchRoleToken())
                                 .name("athenz-role-token/" + domainName + '/' + this.roleNames)
                                 .exceptionHandler(this::errorHandler)
                                 .refreshIf(token -> remainingTimeSec(token) < refreshBeforeSec)
                                 .expireIf(token -> remainingTimeSec(token) == 0)
                                 .build();
    }

    @Override
    public CompletableFuture<String> getToken() {
        return tokenLoader.load().thenApply(RoleToken::getToken);
    }

    private static long remainingTimeSec(RoleToken token) {
        final long expiryTimeSec = token.getExpiryTime() - (System.currentTimeMillis() / 1000);
        return Math.max(expiryTimeSec, 0);
    }

    private CompletableFuture<RoleToken> fetchRoleToken() {
        final WebClientRequestPreparation preparation =
                webClient.prepare()
                         .get("/domain/{domainName}/token")
                         .pathParam("domainName", domainName);
        if (!roleNames.isEmpty()) {
            preparation.queryParam("role", roleNames);
        }
        return preparation
                .asJson(RoleToken.class)
                .execute()
                .handle((response, cause) -> {
                    if (cause != null) {
                        cause = Exceptions.peel(cause);
                        if (cause instanceof InvalidHttpResponseException) {
                            final InvalidHttpResponseException exception = (InvalidHttpResponseException) cause;
                            if (exception.response().status() == HttpStatus.FORBIDDEN) {
                                throw new AccessDeniedException(
                                        "Failed to obtain an Athenz role token. (domain: " + domainName +
                                        ", roles: " + roleNames + ')', exception);
                            }
                        }
                    }
                    return response.content();
                });
    }

    @Nullable
    private CompletableFuture<RoleToken> errorHandler(Throwable cause, @Nullable RoleToken cache) {
        if (cause instanceof InvalidHttpResponseException) {
            final InvalidHttpResponseException exception = (InvalidHttpResponseException) cause;
            if (exception.response().status() == HttpStatus.FORBIDDEN) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(
                        new AccessDeniedException("Failed to obtain an Athenz role token. " +
                                                  "(domain: " + domainName + ", roles: " + roleNames + ')',
                                                  exception));
            }
        }
        return null;
    }
}
