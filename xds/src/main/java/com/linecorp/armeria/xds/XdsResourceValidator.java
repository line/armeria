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

package com.linecorp.armeria.xds;

import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.ServiceLoader;

import com.google.common.collect.Streams;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.validator.XdsValidatorIndex;

/**
 * Per-bootstrap validator that delegates to the highest-priority {@link XdsValidatorIndex} loaded via SPI.
 * Created once in {@link XdsBootstrapBuilder#build()} and threaded through the entire resource pipeline.
 *
 * <p>Validation is performed at exactly two levels:
 * <ul>
 *   <li><b>Static resources</b> — {@link XdsBootstrapImpl} calls {@link #assertValid(Message)} once
 *       on the entire {@code Bootstrap} message at construction time. Both pgv and supported-field
 *       validators recurse into nested messages, so this single call covers all static clusters,
 *       listeners, secrets, and their sub-messages. Inline sub-resources (e.g. {@code VirtualHost}
 *       within a {@code RouteConfiguration}, {@code ClusterLoadAssignment} within a {@code Cluster})
 *       are covered by parent validation and do not need separate calls.</li>
 *   <li><b>Dynamic resources</b> — calls
 *       {@link #assertValid(Message)} on each top-level resource unpacked from a
 *       {@code DiscoveryResponse}. Validation failures are caught and reported as invalid
 *       resources (NACK'd back to the control plane).</li>
 * </ul>
 *
 * <p>In addition, {@link #unpack(Any, Class)} is used for {@code google.protobuf.Any}-typed
 * fields that cannot be validated by parent recursion (since {@code Any} is opaque to protobuf
 * field traversal).
 */
@UnstableApi
public final class XdsResourceValidator {

    private static final XdsValidatorIndex spiValidator =
            Streams.stream(ServiceLoader.load(XdsValidatorIndex.class,
                                              XdsValidatorIndex.class.getClassLoader()))
                   .max(Comparator.comparingInt(XdsValidatorIndex::priority))
                   .orElse(XdsValidatorIndex.noop());

    XdsResourceValidator() {
    }

    /**
     * Validates the given message using the SPI-loaded {@link XdsValidatorIndex}.
     */
    void assertValid(Message message) {
        requireNonNull(message, "message");
        spiValidator.assertValid(message);
    }

    /**
     * Unpacks an {@link Any} message into the given class and validates the result.
     * This is necessary for {@code Any}-typed fields because protobuf treats {@code Any}
     * as an opaque blob — parent-level validation cannot recurse into it.
     */
    public <T extends Message> T unpack(Any message, Class<T> clazz) {
        requireNonNull(message, "message");
        requireNonNull(clazz, "clazz");
        final T unpacked;
        try {
            unpacked = message.unpack(clazz);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Error unpacking: " + clazz.getName(), e);
        }
        assertValid(unpacked);
        return unpacked;
    }
}
