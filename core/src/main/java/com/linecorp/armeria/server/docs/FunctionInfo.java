/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.Service;

/**
 * Metadata about a function of a {@link Service}.
 */
public final class FunctionInfo {

    private final String name;
    private final TypeInfo returnTypeInfo;
    private final List<FieldInfo> parameters;
    private final List<ExceptionInfo> exceptions;
    private final String sampleJsonRequest;
    private final String docString;

    /**
     * Creates a new instance.
     */
    public FunctionInfo(String name,
                        TypeInfo returnTypeInfo,
                        Iterable<FieldInfo> parameters,
                        Iterable<ExceptionInfo> exceptions,
                        @Nullable String sampleJsonRequest,
                        @Nullable String docString) {

        this.name = requireNonNull(name, "name");
        this.returnTypeInfo = requireNonNull(returnTypeInfo, "returnTypeInfo");
        this.parameters = ImmutableList.copyOf(requireNonNull(parameters, "parameters"));
        this.exceptions = ImmutableList.copyOf(requireNonNull(exceptions, "exceptions"));
        this.sampleJsonRequest = sampleJsonRequest;
        this.docString = docString;
    }

    /**
     * Returns the name of the function.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the metadata about the return type of the function.
     */
    @JsonProperty
    public TypeInfo returnTypeInfo() {
        return returnTypeInfo;
    }

    /**
     * Returns the metadata about the parameters of the function.
     */
    @JsonProperty
    public List<FieldInfo> parameters() {
        return parameters;
    }

    /**
     * Returns the metadata about the exceptions declared by the function.
     */
    @JsonProperty
    public List<ExceptionInfo> exceptions() {
        return exceptions;
    }

    /**
     * Returns the sample request of the function, serialized in JSON format.
     */
    @JsonProperty
    public String sampleJsonRequest() {
        return sampleJsonRequest;
    }

    /**
     * Returns the documentation string of the function.
     */
    @JsonProperty
    public String docString() {
        return docString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final FunctionInfo that = (FunctionInfo) o;
        return name.equals(that.name) &&
               returnTypeInfo.equals(that.returnTypeInfo) &&
               parameters.equals(that.parameters) &&
               exceptions.equals(that.exceptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, returnTypeInfo, parameters, exceptions);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("returnTypeInfo", returnTypeInfo)
                          .add("parameters", parameters)
                          .add("exceptions", exceptions)
                          .toString();
    }
}
