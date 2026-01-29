/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.athenz;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An Athenz authorizer that uses Athenz ZPE to authorize access requests.
 */
@UnstableApi
public final class AthenzAuthorizer {

    /**
     * Returns a new builder for creating an {@link AthenzAuthorizer}.
     */
    public static AthenzAuthorizerBuilder builder(ZtsBaseClient ztsBaseClient) {
        requireNonNull(ztsBaseClient, "ztsBaseClient");
        return new AthenzAuthorizerBuilder(ztsBaseClient);
    }

    private final MinifiedAuthZpeClient delegate;

    AthenzAuthorizer(MinifiedAuthZpeClient delegate) {
        this.delegate = delegate;
    }

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the user (cltToken, cltTokenName).
     *
     * <p>Note that this method may perform a network call to the Athenz ZPE server, so it should not be called
     * directly from an event loop.
     *
     * @param token - either role or access token. For role tokens:
     *        value for the HTTP header: Athenz-Role-Auth
     *        ex: "v=Z1;d=angler;r=admin;a=aAkjbbDMhnLX;t=1431974053;e=1431974153;k=0"
     *        For access tokens: value for HTTP header: Authorization: Bearer access-token
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     *
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *                           the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *                           reason why the access was denied
     */
    public AccessCheckStatus authorize(String token, String resource, String action) {
        requireNonNull(token, "token");
        requireNonNull(resource, "resource");
        requireNonNull(action, "action");
        return delegate.allowAccess(token, resource, action);
    }

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the user (cltToken, cltTokenName).
     *
     * @param token - either role or access token. For role tokens:
     *        value for the HTTP header: Athenz-Role-Auth
     *        ex: "v=Z1;d=angler;r=admin;a=aAkjbbDMhnLX;t=1431974053;e=1431974153;k=0"
     *        For access tokens: value for HTTP header: Authorization: Bearer access-token
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     *
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *                           the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *                           reason why the access was denied
     */
    public CompletableFuture<AccessCheckStatus> authorizeAsync(String token, String resource, String action) {
        requireNonNull(token, "token");
        requireNonNull(resource, "resource");
        requireNonNull(action, "action");
        return CompletableFuture.supplyAsync(() -> {
            return delegate.allowAccess(token, resource, action);
        }, CommonPools.blockingTaskExecutor());
    }
}
