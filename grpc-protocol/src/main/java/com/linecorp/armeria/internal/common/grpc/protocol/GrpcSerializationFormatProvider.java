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

package com.linecorp.armeria.internal.common.grpc.protocol;

import static com.linecorp.armeria.common.MediaType.GRPC;
import static com.linecorp.armeria.common.MediaType.GRPC_JSON;
import static com.linecorp.armeria.common.MediaType.GRPC_PROTO;
import static com.linecorp.armeria.common.MediaType.GRPC_WEB;
import static com.linecorp.armeria.common.MediaType.GRPC_WEB_JSON;
import static com.linecorp.armeria.common.MediaType.GRPC_WEB_PROTO;
import static com.linecorp.armeria.common.MediaType.GRPC_WEB_TEXT;
import static com.linecorp.armeria.common.MediaType.GRPC_WEB_TEXT_PROTO;

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
                new Entry("gproto", GRPC_PROTO, GRPC),
                new Entry("gjson", GRPC_JSON),
                new Entry("gproto-web", GRPC_WEB_PROTO, GRPC_WEB),
                new Entry("gjson-web", GRPC_WEB_JSON),
                new Entry("gproto-web-text", GRPC_WEB_TEXT_PROTO, GRPC_WEB_TEXT));
    }

    @Override
    public String toString() {
        return GrpcSerializationFormatProvider.class.getSimpleName();
    }
}
