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

package com.linecorp.armeria.internal.common.grpc;

import static com.linecorp.armeria.common.MediaType.create;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SerializationFormatProvider;

/**
 * {@link SerializationFormatProvider} that provides the gRPC-related {@link SerializationFormat}s.
 */
public final class GrpcSerializationFormatProvider extends SerializationFormatProvider {
    @Override
    protected Set<Entry> entries() {
        return ImmutableSet.of(
                new Entry("gproto", create("application", "grpc+proto"), create("application", "grpc")),
                new Entry("gjson", create("application", "grpc+json")),
                new Entry("gproto-web", create("application", "grpc-web+proto"),
                          create("application", "grpc-web")),
                new Entry("gjson-web", create("application", "grpc-web+json")),
                new Entry("gproto-web-text", create("application", "grpc-web-text+proto"),
                          create("application", "grpc-web-text")));
    }

    @Override
    public String toString() {
        return GrpcSerializationFormatProvider.class.getSimpleName();
    }
}
