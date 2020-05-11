/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.eureka;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableSet;

/**
 * A {@link Set} of {@link Application}s.
 */
@JsonDeserialize(using = ApplicationsDeserializer.class)
@JsonRootName("applications")
public class Applications {

    @Nullable
    private final String appsHashCode;

    private final Set<Application> applications;

    Applications(@Nullable String appsHashCode, Set<Application> applications) {
        this.appsHashCode = appsHashCode;
        this.applications = ImmutableSet.copyOf(requireNonNull(applications, "applications"));
    }

    /**
     * Returns the {@code appsHashCode}.
     */
    @Nullable
    public String appsHashCode() {
        return appsHashCode;
    }

    /**
     * Returns the {@link Set} of {@link Application}s.
     */
    public Set<Application> applications() {
        return applications;
    }
}
