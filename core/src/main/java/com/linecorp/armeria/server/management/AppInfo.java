/*
 * Copyright 2024 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server.management;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A class that represents application information, which can be configured through
 * {@link ManagementService#of(AppInfo)}.
 */
public final class AppInfo {
    @Nullable final String version;
    @Nullable final String name;
    @Nullable final String description;

    /**
     * Creates a new {@link AppInfo} that holds information about an application.
     * @param version A version of an application e.g. "1.0.0"
     * @param name A name of an application
     * @param description A description of application
     */
    public AppInfo(@Nullable String version, @Nullable String name, @Nullable String description) {
        this.version = version;
        this.name = name;
        this.description = description;
    }

    /**
     * Returns the artifact version of the deployed application, such as {@code "1.0.0"}.
     */
    @JsonProperty
    public String getVersion() {
        return version;
    }

    /**
     * Returns the name of the deployed application.
     */
    @JsonProperty
    public String getName() {
        return name;
    }

    /**
     * Returns the description of the deployed application.
     */
    @JsonProperty
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("version", version)
                .add("name", name)
                .add("description", description)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AppInfo appInfo = (AppInfo) o;
        return Objects.equals(version, appInfo.version) &&
               Objects.equals(name, appInfo.name) &&
               Objects.equals(description, appInfo.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, name, description);
    }
}
