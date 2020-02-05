/*
 * Copyright 2016 LINE Corporation
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

import java.util.Map;
import java.util.Set;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.internal.common.thrift.ThriftServiceMetadata;

/**
 * The information about a Thrift service served by {@link THttpService} or {@link ThriftCallService}.
 *
 * @see THttpService#entries()
 * @see ThriftCallService#entries()
 */
public final class ThriftServiceEntry {

    final String name;
    final Iterable<?> implementations;
    final ThriftServiceMetadata metadata;

    ThriftServiceEntry(Map.Entry<String, ? extends Iterable<?>> entry) {
        final String name = entry.getKey();
        final Iterable<?> implementations = entry.getValue();

        requireNonNull(name, "implementations contains an entry with null key.");
        requireNonNull(implementations, "implementations['" + name + "']");

        this.name = name;
        this.implementations = implementations;
        metadata = new ThriftServiceMetadata(implementations);
    }

    /**
     * Returns the service name.
     *
     * @return the service name, or an empty string ({@code ""}) if this service is not multiplexed
     */
    public String name() {
        return name;
    }

    /**
     * Returns the list of {@code *.AsyncIface} or {@code *.Iface} implementations.
     */
    public Iterable<?> implementations() {
        return implementations;
    }

    /**
     * Returns the {@code *.AsyncIface} or {@code *.Iface} classes implemented by
     * {@linkplain #implementations() the implementations}.
     */
    public Set<Class<?>> interfaces() {
        return metadata.interfaces();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper("Entry")
                          .add("name", name())
                          .add("ifaces", interfaces())
                          .add("impl", implementations()).toString();
    }
}
