/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server.docs;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about a descriptive type.
 */
@UnstableApi
public interface DescriptiveTypeInfo {

    /**
     * Returns the fully qualified type name.
     */
    @JsonProperty
    String name();

    /**
     * Returns the description information. If not available, {@link DescriptionInfo#empty()} is returned.
     */
    @JsonProperty
    DescriptionInfo descriptionInfo();

    /**
     * Returns all enum, struct and exception types referred by this type.
     */
    default Set<DescriptiveTypeSignature> findDescriptiveTypes() {
        return ImmutableSet.of();
    }
}
