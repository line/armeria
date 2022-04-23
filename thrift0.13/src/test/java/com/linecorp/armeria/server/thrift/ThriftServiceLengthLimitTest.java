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

package com.linecorp.armeria.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.Name;
import com.linecorp.armeria.service.test.thrift.main.NameSortService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ThriftServiceLengthLimitTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final NameSortService.AsyncIface NAME_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete(name);

    private static final int MAX_REQUEST_LENGTH = 10000;
    private static final int MAX_STRING_LENGTH = 100;
    private static final int MAX_CONTAINER_LENGTH = 200;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.maxRequestLength(MAX_REQUEST_LENGTH);
            sb.service("/default-limit", THttpService.of(HELLO_SERVICE_HANDLER));
            sb.service("/string-limit", THttpService.builder()
                                                    .addService(HELLO_SERVICE_HANDLER)
                                                    .maxRequestStringLength(MAX_STRING_LENGTH)
                                                    .build());

            sb.service("/container-limit", THttpService.builder()
                                                       .addService(NAME_SERVICE_HANDLER)
                                                       .maxRequestContainerLength(MAX_CONTAINER_LENGTH)
                                                       .build());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void defaultLimit(SerializationFormat serializationFormat) throws TException {
        final HelloService.Iface client =
                ThriftClients.newClient(server.httpUri(serializationFormat).resolve("/default-limit"),
                                        HelloService.Iface.class);
        final Throwable cause = catchThrowable(() -> {
            client.hello(Strings.repeat("a", MAX_REQUEST_LENGTH + 1));
        });
        assertThat(cause).isInstanceOf(TTransportException.class);
        assertThat(cause.getCause())
                .isInstanceOf(InvalidResponseHeadersException.class)
                .satisfies(ex -> {
                    assertThat(((InvalidResponseHeadersException) ex).headers().status())
                            .isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
                });

        final ByteBuf buf = Unpooled.buffer();
        // Set a spurious message size.
        buf.writeInt(MAX_REQUEST_LENGTH + 1);
        buf.writeBytes("Hello".getBytes());
        final AggregatedHttpResponse response =
                server.blockingWebClient()
                      .prepare()
                      .post("/default-limit")
                      .content(ThriftSerializationFormats.BINARY.mediaType(), HttpData.wrap(buf))
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.contentUtf8()).contains("Length exceeded max allowed: " + (MAX_REQUEST_LENGTH + 1));
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void stringLimit(SerializationFormat serializationFormat) {
        final HelloService.Iface client =
                ThriftClients.newClient(server.httpUri(serializationFormat).resolve("/string-limit"),
                                        HelloService.Iface.class);
        assertThatThrownBy(() -> {
            client.hello(Strings.repeat("a", MAX_STRING_LENGTH + 1));
        }).isInstanceOf(TApplicationException.class)
          .hasMessageContaining("Length exceeded max allowed: " + (MAX_STRING_LENGTH + 1));
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void containerLimit(SerializationFormat serializationFormat) {
        final NameSortService.Iface client =
                ThriftClients.newClient(server.httpUri(serializationFormat).resolve("/container-limit"),
                                        NameSortService.Iface.class);
        final List<Name> names = new ArrayList<>();
        for (int i = 0; i < MAX_CONTAINER_LENGTH + 1; i++) {
            names.add(new Name("a", "b", "c"));
        }
        assertThatThrownBy(() -> {
            client.sort(names);
        }).isInstanceOf(TApplicationException.class)
          .hasMessageContaining("Length exceeded max allowed: " + (MAX_CONTAINER_LENGTH + 1));
    }

    private static class ThriftSerializationFormatProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(ThriftSerializationFormats.BINARY, ThriftSerializationFormats.COMPACT)
                         .map(Arguments::of);
        }
    }
}
