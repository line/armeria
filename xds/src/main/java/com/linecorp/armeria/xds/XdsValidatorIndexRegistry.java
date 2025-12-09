/*
 * Copyright 2025 LY Corporation
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

import java.util.Comparator;
import java.util.ServiceLoader;

import com.google.common.collect.Streams;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.xds.validator.XdsValidatorIndex;

final class XdsValidatorIndexRegistry {

    private static final XdsValidatorIndex xdsValidatorIndex =
            Streams.stream(ServiceLoader.load(XdsValidatorIndex.class,
                                              XdsValidatorIndex.class.getClassLoader()))
                   .max(Comparator.comparingInt(XdsValidatorIndex::priority))
                   .orElse(XdsValidatorIndex.noop());

    static void assertValid(Object message) {
        xdsValidatorIndex.assertValid(message);
    }

    static <T extends Message> T unpack(Any message, Class<T> clazz) {
        final T unpacked;
        try {
            unpacked = message.unpack(clazz);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e);
        }
        xdsValidatorIndex.assertValid(unpacked);
        return unpacked;
    }

    private XdsValidatorIndexRegistry() {
    }
}
