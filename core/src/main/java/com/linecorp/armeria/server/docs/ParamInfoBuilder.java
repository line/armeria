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
package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Creates a new {@link ParamInfo} using the builder pattern.
 */
@UnstableApi
public final class ParamInfoBuilder {

    private final String name;
    private final TypeSignature typeSignature;

    private FieldRequirement requirement = FieldRequirement.UNSPECIFIED;
    private FieldLocation location = FieldLocation.UNSPECIFIED;

    ParamInfoBuilder(String name, TypeSignature typeSignature) {
        this.name = requireNonNull(name, "name");
        this.typeSignature = requireNonNull(typeSignature, "typeSignature");
    }

    /**
     * Sets the {@link FieldRequirement} of the parameter.
     */
    public ParamInfoBuilder requirement(FieldRequirement requirement) {
        this.requirement = requireNonNull(requirement, "requirement");
        return this;
    }

    /**
     * Sets the {@link FieldLocation} of the parameter.
     */
    public ParamInfoBuilder location(FieldLocation location) {
        this.location = requireNonNull(location, "location");
        return this;
    }

    /**
     * Returns a newly-created {@link ParamInfo} based on the properties of this builder.
     */
    public ParamInfo build() {
        return new ParamInfo(name, location, requirement, typeSignature);
    }
}
