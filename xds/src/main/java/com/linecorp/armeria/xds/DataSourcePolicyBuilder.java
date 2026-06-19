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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for {@link DataSourcePolicy}.
 */
@UnstableApi
public final class DataSourcePolicyBuilder {

    private Set<Path> allowedRootDirs = ImmutableSet.of();
    private Set<String> allowedEnvVars = ImmutableSet.of();

    DataSourcePolicyBuilder() {}

    /**
     * Sets the allowed root directories for file-based DataSources.
     * Both relative and absolute paths are accepted. If not set, all file paths are allowed.
     */
    public DataSourcePolicyBuilder allowedRootDirs(Path... dirs) {
        requireNonNull(dirs, "dirs");
        final ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
        for (Path dir : dirs) {
            builder.add(requireNonNull(dir, "dir").toAbsolutePath().normalize());
        }
        allowedRootDirs = builder.build();
        return this;
    }

    /**
     * Sets the allowed environment variable names for environment-variable-based DataSources.
     * If not set, all environment variables are allowed.
     */
    public DataSourcePolicyBuilder allowedEnvironmentVariables(String... names) {
        requireNonNull(names, "names");
        final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (String name : names) {
            builder.add(requireNonNull(name, "name"));
        }
        allowedEnvVars = builder.build();
        return this;
    }

    /**
     * Builds a new {@link DataSourcePolicy}.
     */
    public DataSourcePolicy build() {
        if (allowedRootDirs.isEmpty() && allowedEnvVars.isEmpty()) {
            return DataSourcePolicy.allowAll();
        }
        return new DataSourcePolicy(allowedRootDirs, allowedEnvVars);
    }
}
