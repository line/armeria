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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.TokenType;

@UnstableApi
@FunctionalInterface
public interface TokenClient {

    CompletableFuture<String> getToken();

    static TokenClient of(ZtsBaseClient ztsBaseClient, String domainName, List<String> roleNames,
                          TokenType tokenType, Duration refreshBefore) {
        requireNonNull(ztsBaseClient, "ztsBaseClient");
        requireNonNull(domainName, "domainName");
        requireNonNull(roleNames, "roleNames");
        requireNonNull(tokenType, "tokenType");
        requireNonNull(refreshBefore, "refreshBefore");
        final ImmutableList<String> immutableRoleNames = ImmutableList.copyOf(roleNames);
        if (tokenType.isRoleToken()) {
            return new RoleTokenClient(ztsBaseClient, domainName, immutableRoleNames, refreshBefore);
        } else {
            return new AccessTokenClient(ztsBaseClient, domainName, immutableRoleNames, refreshBefore);
        }
    }
}
