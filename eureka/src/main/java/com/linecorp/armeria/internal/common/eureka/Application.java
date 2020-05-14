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

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.google.common.collect.ImmutableSet;

/**
 * An application.
 */
@JsonRootName("application")
public final class Application {

    private final String name;

    private final Set<InstanceInfo> instances;

    /**
     * Creates a new instance.
     */
    public Application(@JsonProperty("name") String name,
                       @JsonProperty("instance") Set<InstanceInfo> instances) {
        this.name = requireNonNull(name, "name");
        this.instances = ImmutableSet.copyOf(requireNonNull(instances, "instances"));
    }

    /**
     * Returns the name of the {@link Application}.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the {@link Set} of {@link InstanceInfo} of the {@link Application}.
     */
    public Set<InstanceInfo> instances() {
        return instances;
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("name", name)
                                   .add("instances", instances)
                                   .toString();
    }
}
