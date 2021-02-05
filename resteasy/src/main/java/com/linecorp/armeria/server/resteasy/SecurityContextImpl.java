/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import static java.util.Objects.requireNonNull;

import java.security.Principal;

import javax.annotation.Nullable;
import javax.ws.rs.core.SecurityContext;

import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Implements {@link SecurityContext}.
 */
@UnstableApi
final class SecurityContextImpl implements SecurityContext {

    public static SecurityContext basic(Principal principal, SecurityDomain securityDomain) {
        return new SecurityContextImpl(principal, securityDomain, "BASIC", true);
    }

    public static SecurityContext insecure() {
        return new SecurityContextImpl();
    }

    @Nullable
    private final Principal principal;

    @Nullable
    private final SecurityDomain securityDomain;

    @Nullable
    private final String authScheme;

    private final boolean isSecure;

    private SecurityContextImpl(Principal principal, SecurityDomain securityDomain, String authScheme,
                                boolean isSecure) {
        this.principal = requireNonNull(principal, "principal");
        this.securityDomain = requireNonNull(securityDomain, "securityDomain");
        this.authScheme = requireNonNull(authScheme, "authScheme");
        this.isSecure = isSecure;
    }

    private SecurityContextImpl() {
        principal = null;
        securityDomain = null;
        authScheme = null;
        isSecure = false;
    }

    @Nullable
    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isUserInRole(String role) {
        return securityDomain != null && securityDomain.isUserInRole(principal, role);
    }

    @Override
    public boolean isSecure() {
        return isSecure;
    }

    @Nullable
    @Override
    public String getAuthenticationScheme() {
        return authScheme;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("isSecure", isSecure)
                          .add("authScheme", authScheme)
                          .toString();
    }
}
