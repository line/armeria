/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.grpc.GrpcJsonMarshallType;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;

import example.armeria.grpc.Proto2ServiceGrpc;
import example.armeria.grpc.Proto3ServiceGrpc;

class GrpcJsonMarshallerTest {

    @Test
    void testDefaultMarshallerDelegate() {
        GrpcJsonMarshaller grpcJsonMarshaller =
                GrpcJsonMarshaller.of(Proto2ServiceGrpc.getServiceDescriptor());
        assertThat(grpcJsonMarshaller).isInstanceOf(DefaultJsonMarshaller.class);
        DefaultJsonMarshaller defaultJsonMarshaller = (DefaultJsonMarshaller) grpcJsonMarshaller;
        assertThat(defaultJsonMarshaller.delegate()).isInstanceOf(UpstreamJsonMarshaller.class);

        grpcJsonMarshaller = GrpcJsonMarshaller.of(Proto3ServiceGrpc.getServiceDescriptor());
        assertThat(grpcJsonMarshaller).isInstanceOf(DefaultJsonMarshaller.class);
        defaultJsonMarshaller = (DefaultJsonMarshaller) grpcJsonMarshaller;
        assertThat(defaultJsonMarshaller.delegate()).isInstanceOf(ProtobufJacksonJsonMarshaller.class);
    }

    @Test
    void testUpstreamMarshaller() {
        final GrpcJsonMarshaller grpcJsonMarshaller =
                GrpcJsonMarshaller.builder()
                                  .grpcJsonMarshallType(GrpcJsonMarshallType.UPSTREAM)
                                  .build(Proto3ServiceGrpc.getServiceDescriptor());
        assertThat(grpcJsonMarshaller).isInstanceOf(UpstreamJsonMarshaller.class);
    }

    @Test
    void testProtobufJacksonMarshaller() {
        final GrpcJsonMarshaller grpcJsonMarshaller =
                GrpcJsonMarshaller.builder()
                                  .grpcJsonMarshallType(GrpcJsonMarshallType.PROTOBUF_JACKSON)
                                  .build(Proto3ServiceGrpc.getServiceDescriptor());
        assertThat(grpcJsonMarshaller).isInstanceOf(ProtobufJacksonJsonMarshaller.class);
    }
}
