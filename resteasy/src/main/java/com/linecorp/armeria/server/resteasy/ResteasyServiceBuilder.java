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

import javax.annotation.Nullable;

import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.spi.ResteasyDeployment;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds {@link ResteasyService}.
 */
@UnstableApi
public final class ResteasyServiceBuilder {

    private final ResteasyDeployment deployment;
    private String contextPath = "/";
    @Nullable
    private SecurityDomain securityDomain;

    ResteasyServiceBuilder(ResteasyDeployment deployment) {
        this.deployment = requireNonNull(deployment, "deployment");
    }

    /**
     * Sets the context path for {@link ResteasyService}.
     */
    public ResteasyServiceBuilder path(String contextPath) {
        this.contextPath = requireNonNull(contextPath, "contextPath");
        if (contextPath.isEmpty()) {
            this.contextPath = "/";
        } else if (!contextPath.startsWith("/")) {
            this.contextPath = '/' + contextPath;
        } else {
            this.contextPath = contextPath;
        }
        return this;
    }

    /**
     * Sets the {@link SecurityDomain} for {@link ResteasyService}.
     */
    public ResteasyServiceBuilder securityDomain(SecurityDomain securityDomain) {
        this.securityDomain = requireNonNull(securityDomain, "securityDomain");
        return this;
    }

    /**
     * Builds new {@link ResteasyService}.
     */
    public ResteasyService build() {
        return new ResteasyService(deployment, contextPath, securityDomain);
    }
}
