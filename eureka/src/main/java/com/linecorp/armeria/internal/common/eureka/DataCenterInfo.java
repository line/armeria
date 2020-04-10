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

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.eureka.DataCenterName;

/**
 * The data center information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonSerialize(using = DataCenterInfoSerializer.class)
public final class DataCenterInfo {

    private final DataCenterName name;

    private final Map<String, String> metadata;

    /**
     * Creates a new instance.
     */
    public DataCenterInfo(@JsonProperty("name") DataCenterName name,
                          @JsonProperty("metadata") Map<String, String> metadata) {
        this.name = name;
        this.metadata = metadata;
    }

    public DataCenterName getName() {
        return name;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataCenterInfo)) {
            return false;
        }
        final DataCenterInfo that = (DataCenterInfo) o;
        return name == that.name &&
               Objects.equal(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name, metadata);
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("name", name)
                                   .add("metadata", metadata)
                                   .toString();
    }
}
