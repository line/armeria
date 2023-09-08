/*
 *  Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.thrift.ThriftMessageTestUtil.newMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TTransport;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.common.thrift.TByteBufTransport;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import testing.thrift.main.HelloService;
import testing.thrift.main.Name;
import testing.thrift.main.NameSortService;

class ThriftClientLengthLimitTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (size, resultHandler) -> resultHandler.onComplete(Strings.repeat("a", Integer.parseInt(size)));

    private static final NameSortService.AsyncIface NAME_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete(name);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.maxRequestLength(0);

            sb.service("/thrift", THttpService.builder()
                                              .addService(HELLO_SERVICE_HANDLER)
                                              .addService(NAME_SERVICE_HANDLER)
                                              .build());

            sb.service("/invalid-string", (ctx, req) -> {
                final SerializationFormat serializationFormat;
                if (ThriftSerializationFormats.BINARY.mediaType().equals(req.contentType())) {
                    serializationFormat = ThriftSerializationFormats.BINARY;
                } else {
                    serializationFormat = ThriftSerializationFormats.COMPACT;
                }
                final ByteBuf buf = newMessage(serializationFormat,
                                               (int) (Flags.defaultMaxResponseLength() + 1),
                                               "Hello".getBytes());
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), HttpData.wrap(buf));
            });

            sb.service("/invalid-container", (ctx, req) -> {
                final ByteBuf buf = ctx.alloc().buffer(128);
                final SerializationFormat serializationFormat;

                if (ThriftSerializationFormats.BINARY.mediaType().equals(req.contentType())) {
                    serializationFormat = ThriftSerializationFormats.BINARY;
                } else {
                    serializationFormat = ThriftSerializationFormats.COMPACT;
                }
                final TMessage header = new TMessage("sort", TMessageType.REPLY, 1);
                final TTransport transport = new TByteBufTransport(buf);
                final TProtocol outProto = ThriftSerializationFormats.protocolFactory(serializationFormat, 0, 0)
                                                                     .getProtocol(transport);
                outProto.writeMessageBegin(header);
                final TField field = new TField("success", TType.LIST, (short) 0);
                outProto.writeFieldBegin(field);
                // Set an invalid container size larger than the container limit.
                outProto.writeListBegin(new TList(TType.STRUCT, (int) (Flags.defaultMaxResponseLength() + 1)));
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), HttpData.wrap(buf));
            });
        }
    };

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void defaultStringLimit(SerializationFormat serializationFormat) throws TException {
        final HelloService.Iface client =
                ThriftClients.newClient(server.httpUri(serializationFormat).resolve("/thrift"),
                                        HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        // Should respect maxResponseLength if maxStringLength is unspecified.
        final int maxStringLength = (int) params.options().maxResponseLength();

        final int responseSize = maxStringLength - 30;
        final String response = client.hello(String.valueOf(responseSize));
        assertThat(response).hasSize(responseSize);

        assertThatThrownBy(() -> {
            final HelloService.Iface client0 =
                    ThriftClients.newClient(server.httpUri(serializationFormat).resolve("/invalid-string"),
                                            HelloService.Iface.class);
            client0.hello("Hello");
        }).isInstanceOf(TProtocolException.class)
          .hasMessageContaining("Length exceeded max allowed: %s", maxStringLength + 1);
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void defaultContainerLimit(SerializationFormat serializationFormat) throws TException {
        final NameSortService.Iface client =
                ThriftClients.newClient(server.httpUri(serializationFormat).resolve("/invalid-container"),
                                        NameSortService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        // Should respect maxResponseLength if maxContainerLength is unspecified.
        final int maxContainerLength = (int) params.options().maxResponseLength();

        assertThatThrownBy(() -> {
            client.sort(ImmutableList.of(new Name("", "", "")));
        }).isInstanceOf(TProtocolException.class)
          .hasMessageContaining("Length exceeded max allowed: " + (maxContainerLength + 1));
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void customStringLimit(SerializationFormat serializationFormat) {
        final int maxStringLength = 100;
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri(serializationFormat))
                             .path("/thrift")
                             .maxResponseStringLength(maxStringLength)
                             .build(HelloService.Iface.class);
        assertThatThrownBy(() -> {
            client.hello(String.valueOf(maxStringLength + 1));
        }).isInstanceOf(TProtocolException.class)
          .hasMessageContaining("Length exceeded max allowed: " + (maxStringLength + 1));
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void customContainerLimit(SerializationFormat serializationFormat) {
        final int maxContainerLength = 100;
        final NameSortService.Iface client =
                ThriftClients.builder(server.httpUri(serializationFormat))
                             .path("/thrift")
                             .maxResponseContainerLength(maxContainerLength)
                             .build(NameSortService.Iface.class);

        final List<Name> names = new ArrayList<>();
        for (int i = 0; i < maxContainerLength + 1; i++) {
            names.add(new Name("a", "b", "c"));
        }
        assertThatThrownBy(() -> {
            client.sort(names);
        }).isInstanceOf(TProtocolException.class)
          .hasMessageContaining("Length exceeded max allowed: " + (maxContainerLength + 1));
    }

    private static class ThriftSerializationFormatProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(ThriftSerializationFormats.BINARY, ThriftSerializationFormats.COMPACT)
                         .map(Arguments::of);
        }
    }
}
