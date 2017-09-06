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

import com.linecorp.armeria.internal.thrift.ThriftServiceMetadata;

/**
 * The information about a Thrift service served by {@link THttpService} or {@link ThriftCallService}.
 *
 * @see THttpService#entries()
 * @see ThriftCallService#entries()
 */
public final class ThriftServiceEntry {

    final String name;
    final Object implementation;
    final ThriftServiceMetadata metadata;

    ThriftServiceEntry(Map.Entry<String, ?> entry) {
        final String name = entry.getKey();
        final Object implementation = entry.getValue();

        requireNonNull(name, "implementations contains an entry with null key.");
        requireNonNull(implementation, "implementations['" + name + "']");

        this.name = name;
        this.implementation = implementation;
        metadata = new ThriftServiceMetadata(implementation);
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
     * Returns the {@code *.AsyncIface} or {@code *.Iface} implementation.
     */
    public Object implementation() {
        return implementation;
    }

    /**
     * Returns the {@code *.AsyncIface} or {@code *.Iface} classes implemented by
     * {@linkplain #implementation() the implementation}.
     */
    public Set<Class<?>> interfaces() {
        return metadata.interfaces();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper("Entry")
                          .add("name", name())
                          .add("ifaces", interfaces())
                          .add("impl", implementation()).toString();
    }
}
