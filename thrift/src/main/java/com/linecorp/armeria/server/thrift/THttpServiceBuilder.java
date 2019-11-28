/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

// TODO add decorator facility
public class THttpServiceBuilder {

    private static final SerializationFormat[] EMPTY_FORMATS = new SerializationFormat[0];
    private final Map<String, List<?>> implementations = new HashMap<>();
    private SerializationFormat defaultSerializationFormat = ThriftSerializationFormats.BINARY;
    private Set<SerializationFormat> otherSerializationFormats = ThriftSerializationFormats.values();

    private static Map<String, List<?>> copyToImmutable(Map<String, List<?>> copy) {
        final Builder<String, List<?>> builder = ImmutableMap.builder();

        copy.forEach((name, implementations) -> {
            builder.put(name, ImmutableList.copyOf(implementations));
        });

        return builder.build();
    }

    public THttpServiceBuilder addService(String name, Object implementation) {
        addByCreatingListIfNecessary(name, implementation);
        return this;
    }

    public THttpServiceBuilder addService(Object implementation) {
        addByCreatingListIfNecessary("", implementation);
        return this;
    }

    public THttpServiceBuilder defaultSerialization(SerializationFormat defaultSerializationFormat) {
        requireNonNull(defaultSerializationFormat, "defaultSerializationFormat");

        this.defaultSerializationFormat = defaultSerializationFormat;
        return this;
    }

    public THttpServiceBuilder otherSerializationFormats(SerializationFormat... otherSerializationFormats) {
        requireNonNull(otherSerializationFormats, "otherSerializationFormats");

        this.otherSerializationFormats = new LinkedHashSet<>();
        this.otherSerializationFormats.addAll(Arrays.asList(otherSerializationFormats));
        return this;
    }

    public THttpService build() {
        final ThriftCallService delegate = new ThriftCallService(copyToImmutable(implementations));

        final LinkedHashSet<SerializationFormat> combined = new LinkedHashSet<>();
        combined.add(defaultSerializationFormat);
        combined.addAll(otherSerializationFormats);

        return new THttpService(delegate, combined.toArray(EMPTY_FORMATS));
    }

    private void addByCreatingListIfNecessary(String name, Object implementation) {
        requireNonNull(name, "name");
        requireNonNull(implementation, "implementation");

        List<?> implementationsList = implementations.get(name);

        if (implementationsList == null) {
            implementationsList = new ArrayList<>();
        }

        implementations.put(name, implementationsList);
    }
}
