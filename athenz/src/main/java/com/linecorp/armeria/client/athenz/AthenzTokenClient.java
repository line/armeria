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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A client that fetches a token from Athenz ZTS and refreshes it when necessary.
 * If you want to add an Athenz token to the {@link RequestHeaders}, use {@link AthenzClient} instead of
 * this interface.
 *
 * <p>This interface is useful when you want to manage the token yourself or use the token for other clients.
 * For example, you can use this interface to fetch a token and add it to the request headers of a Spring
 * {@code WebClient} or {@code RestTemplate}.
 *
 * <p>Example:
 * <pre>{@code
 * ZtsBaseClient ztsBaseClient = ...;
 *
 * AthenzTokenClient tokenClient =
 *     AthenzTokenClient.builder(ztsBaseClient)
 *                       .domainName("my-domain")
 *                       .roleNames("my-role")
 *                       .build();
 *
 * tokenClient.getToken().thenAccept(token -> {
 *     // Use the token for your own client.
 * });
 * }</pre>
 */
@UnstableApi
public interface AthenzTokenClient {

    /**
     * Returns a new {@link AthenzTokenClientBuilder} based on the specified {@link ZtsBaseClient}.
     */
    static AthenzTokenClientBuilder builder(ZtsBaseClient ztsBaseClient) {
        return new AthenzTokenClientBuilder(ztsBaseClient);
    }

    /**
     * Returns the domain name for which the token is fetched from Athenz ZTS.
     */
    String domainName();

    /**
     * Returns the role names for which the token is fetched from Athenz ZTS.
     */
    List<String> roleNames();

    /**
     * Returns a token. The returned {@link CompletableFuture} is completed when the token is successfully fetched
     * from Athenz ZTS. The returned {@link CompletableFuture} is completed exceptionally if the token cannot be
     * fetched from Athenz ZTS.
     */
    CompletableFuture<String> getToken();
}
