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

package com.linecorp.armeria.xds;

import java.nio.file.Path;
import java.util.Set;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A policy that restricts which file paths and environment variables may be accessed
 * by {@code DataSource} resources.
 *
 * <p>By default, all file paths and environment variables are allowed (matching Envoy behavior).
 * When allowed root directories or environment variable names are configured, only matching
 * paths and variables are permitted.
 *
 * @see XdsBootstrapBuilder#dataSourcePolicy(DataSourcePolicy)
 */
@UnstableApi
public final class DataSourcePolicy {

    private static final DataSourcePolicy ALLOW_ALL = new DataSourcePolicy(
            ImmutableSet.of(), ImmutableSet.of());

    private final Set<Path> allowedRootDirs;
    private final Set<String> allowedEnvironmentVariables;

    DataSourcePolicy(Set<Path> allowedRootDirs, Set<String> allowedEnvironmentVariables) {
        this.allowedRootDirs = allowedRootDirs;
        this.allowedEnvironmentVariables = allowedEnvironmentVariables;
    }

    /**
     * Returns an {@link DataSourcePolicy} that allows all file paths and environment variables.
     */
    public static DataSourcePolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Returns a new {@link DataSourcePolicyBuilder}.
     */
    public static DataSourcePolicyBuilder builder() {
        return new DataSourcePolicyBuilder();
    }

    boolean isFileAllowed(Path path) {
        // All paths are expected to be absolute/normalized.
        if (allowedRootDirs.isEmpty()) {
            return true;
        }
        for (Path root : allowedRootDirs) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the specified environment variable name is allowed by this policy.
     * If no allowed environment variable names are configured, all variables are allowed.
     */
    boolean isEnvVarAllowed(String envVar) {
        if (allowedEnvironmentVariables.isEmpty()) {
            return true;
        }
        return allowedEnvironmentVariables.contains(envVar);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("allowedRootDirs", allowedRootDirs)
                          .add("allowedEnvironmentVariables", allowedEnvironmentVariables)
                          .toString();
    }
}
