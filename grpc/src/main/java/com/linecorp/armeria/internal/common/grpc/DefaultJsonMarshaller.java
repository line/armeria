/*
 * Copyright 2020 LINE Corporation
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

import org.curioswitch.common.protobuf.json.MessageMarshaller.Builder;

import com.google.protobuf.Descriptors.FileDescriptor.Syntax;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;

import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;

public final class DefaultJsonMarshaller implements GrpcJsonMarshaller {

    private final GrpcJsonMarshaller delegate;

    public DefaultJsonMarshaller(ServiceDescriptor serviceDescriptor,
                                 @Nullable Consumer<Builder> jsonMarshallerCustomizer) {
        final Object schemaDescriptor = serviceDescriptor.getSchemaDescriptor();
        if (schemaDescriptor instanceof ProtoFileDescriptorSupplier) {
            final Syntax syntax =
                    ((ProtoFileDescriptorSupplier) schemaDescriptor).getFileDescriptor().getSyntax();
            if (syntax == Syntax.PROTO2) {
                delegate = UpstreamJsonMarshaller.INSTANCE;
                return;
            }
        }
        delegate = GrpcJsonUtil.protobufJacksonJsonMarshaller(serviceDescriptor, jsonMarshallerCustomizer);
    }

    @Override
    public <T> void serializeMessage(Marshaller<T> marshaller, T message, OutputStream os) throws IOException {
        delegate.serializeMessage(marshaller, message, os);
    }

    @Override
    public <T> T deserializeMessage(Marshaller<T> marshaller, InputStream is) throws IOException {
        return delegate.deserializeMessage(marshaller, is);
    }
}
