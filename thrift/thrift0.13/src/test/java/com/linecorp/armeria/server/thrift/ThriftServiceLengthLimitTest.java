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

import static com.linecorp.armeria.common.thrift.ThriftMessageTestUtil.newMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import testing.thrift.main.HelloService;
import testing.thrift.main.Name;
import testing.thrift.main.NameSortService;

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
            sb.service("/default-limit", THttpService.builder()
                                                     .addService(HELLO_SERVICE_HANDLER)
                                                     .addService(NAME_SERVICE_HANDLER)
                                                     .build());
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
    void defaultStringLimit(SerializationFormat serializationFormat) throws TException {
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

        final ByteBuf buf = newMessage(serializationFormat, MAX_REQUEST_LENGTH + 1, "Hello".getBytes());
        final AggregatedHttpResponse response =
                server.blockingWebClient()
                      .prepare()
                      .post("/default-limit")
                      .content(serializationFormat.mediaType(), HttpData.wrap(buf))
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.contentUtf8()).contains("Length exceeded max allowed: " + (MAX_REQUEST_LENGTH + 1));
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void defaultContainerLimit(SerializationFormat serializationFormat) throws Exception {
        final AtomicReference<RequestHeaders> capturedHeaders = new AtomicReference<>();
        final AtomicReference<byte[]> capturedMessage = new AtomicReference<>();
        final NameSortService.Iface client =
                ThriftClients.builder(server.httpUri(serializationFormat).resolve("/default-limit"))
                             .decorator((delegate, ctx, req) -> {

                                 capturedHeaders.set(req.headers());
                                 final HttpRequest peeked =
                                         req.peekData(data -> capturedMessage.set(data.array()));
                                 ctx.updateRequest(peeked);
                                 return delegate.execute(ctx, peeked);
                             })
                             .build(NameSortService.Iface.class);
        final List<Name> names = new ArrayList<>();
        final int containerLength = MAX_REQUEST_LENGTH + 1;
        for (int i = 0; i < containerLength; i++) {
            names.add(new Name("a", "b", "c"));
        }
        // Limited by the HTTP layer.
        final Throwable cause = catchThrowable(() -> client.sort(names));
        assertThat(cause).isInstanceOf(TTransportException.class);
        assertThat(cause.getCause())
                .isInstanceOf(InvalidResponseHeadersException.class)
                .satisfies(ex -> {
                    assertThat(((InvalidResponseHeadersException) ex).headers().status())
                            .isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
                });

        // Limited by the `TProtocol`s.
        final ServiceConfig serviceConfig =
                server.server().serviceConfigs().stream()
                      .filter(cfg -> "/default-limit".equals(cfg.route().patternString()))
                      .findFirst().get();
        final HttpService service = serviceConfig.service();
        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(capturedHeaders.get(), HttpData.wrap(capturedMessage.get())));
        final AggregatedHttpResponse response = service.serve(ctx, ctx.request()).aggregate().join();
        assertThat(response.contentUtf8())
                .contains("Length exceeded max allowed: " + containerLength);
        assertThat(ctx.log().whenAvailable(RequestLogProperty.RESPONSE_CAUSE).join().responseCause())
                .isInstanceOf(TApplicationException.class)
                .hasMessageContaining("Length exceeded max allowed: " + containerLength);
    }

    @ArgumentsSource(ThriftSerializationFormatProvider.class)
    @ParameterizedTest
    void customStringLimit(SerializationFormat serializationFormat) {
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
    void customContainerLimit(SerializationFormat serializationFormat) {
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
