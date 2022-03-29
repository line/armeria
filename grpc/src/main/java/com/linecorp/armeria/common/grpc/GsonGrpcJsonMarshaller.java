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

package com.linecorp.armeria.common.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;

final class GsonGrpcJsonMarshaller implements GrpcJsonMarshaller {

    final JsonFormat.Printer printer;
    final JsonFormat.Parser parser;

    GsonGrpcJsonMarshaller(JsonFormat.Printer printer, JsonFormat.Parser parser) {
        this.printer = printer;
        this.parser = parser;
    }

    @Override
    public <T> void serializeMessage(Marshaller<T> marshaller, T message, OutputStream os) throws IOException {
        os.write(printer.print((MessageOrBuilder) message).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public <T> T deserializeMessage(Marshaller<T> marshaller, InputStream is) throws IOException {
        final PrototypeMarshaller<T> prototypeMarshaller = (PrototypeMarshaller<T>) marshaller;
        final Message prototype = (Message) prototypeMarshaller.getMessagePrototype();
        assert prototype != null;
        final Message.Builder builder = prototype.newBuilderForType();
        parser.merge(new InputStreamReader(is, StandardCharsets.UTF_8), builder);
        @SuppressWarnings("unchecked")
        final T cast = (T) builder.build();
        return cast;
    }
}
