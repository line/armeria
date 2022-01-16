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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;

import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;

public final class ProtobufJacksonJsonMarshaller implements GrpcJsonMarshaller {

    private final MessageMarshaller delegate;

    public ProtobufJacksonJsonMarshaller(MessageMarshaller delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> void serializeMessage(Marshaller<T> marshaller, T message, OutputStream os) throws IOException {
        delegate.writeValue((Message) message, os);
    }

    @Override
    public <T> T deserializeMessage(Marshaller<T> marshaller, InputStream is) throws IOException {
        final PrototypeMarshaller<T> prototypeMarshaller = (PrototypeMarshaller<T>) marshaller;
        final Message prototype = (Message) prototypeMarshaller.getMessagePrototype();
        final Message.Builder builder = prototype.newBuilderForType();
        delegate.mergeValue(is, builder);
        @SuppressWarnings("unchecked")
        final T cast = (T) builder.build();
        return cast;
    }
}
