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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimaps;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

// TODO add decorator facility
public class THttpServiceBuilder {

    private static final SerializationFormat[] EMPTY_FORMATS = new SerializationFormat[0];
    private final ImmutableListMultimap.Builder<String, Object> implementationsBuilder =
            ImmutableListMultimap.builder();
    private SerializationFormat defaultSerializationFormat = ThriftSerializationFormats.BINARY;
    private Set<SerializationFormat> otherSerializationFormats = ThriftSerializationFormats.values();

    public THttpServiceBuilder addService(String name, Object implementation) {
        implementationsBuilder.put(name, implementation);
        return this;
    }

    public THttpServiceBuilder addService(Object implementation) {
        implementationsBuilder.put("", implementation);
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

        @SuppressWarnings("UnstableApiUsage")
        final Map<String, List<Object>> implementations = Multimaps.asMap(implementationsBuilder.build());
        final ThriftCallService delegate = ThriftCallService.of(implementations);

        final LinkedHashSet<SerializationFormat> combined = new LinkedHashSet<>();
        combined.add(defaultSerializationFormat);
        combined.addAll(otherSerializationFormats);

        return new THttpService(delegate, combined.toArray(EMPTY_FORMATS));
    }
}
