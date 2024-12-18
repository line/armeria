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
package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reflections.ReflectionUtils;

import com.linecorp.armeria.client.AbstractClientOptionsBuilder;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.client.ClientBuilderParamsUtil;
import com.linecorp.armeria.internal.client.endpoint.FailingEndpointGroup;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import testing.thrift.main.HelloService;

class ThriftClientBuilderTest {

    @Test
    void uri() throws Exception {
        final HelloService.Iface client = ThriftClients.builder("https://google.com/")
                                                       .build(HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(params).isNotNull();
        assertThat(params.uri().toString()).isEqualTo("tbinary+https://google.com/");
    }

    @Test
    void endpointWithoutPath() {
        final HelloService.Iface client = ThriftClients.builder("http", Endpoint.of("127.0.0.1"))
                                                       .build(HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(params).isNotNull();
        assertThat(params.uri().toString()).isEqualTo("tbinary+http://127.0.0.1/");
    }

    @Test
    void endpointWithPath() {
        final HelloService.Iface client = ThriftClients.builder("tbinary+http", Endpoint.of("127.0.0.1"))
                                                       .path("/foo")
                                                       .build(HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(params).isNotNull();
        assertThat(params.uri().toString()).isEqualTo("tbinary+http://127.0.0.1/foo");
        assertThat(params.scheme().serializationFormat()).isSameAs(ThriftSerializationFormats.BINARY);
    }

    @Test
    void undefinedProtocol() {
        assertThatThrownBy(() -> ThriftClients
                .newClient(Scheme.of(ThriftSerializationFormats.BINARY, SessionProtocol.UNDEFINED),
                           Endpoint.of("1.2.3.4"), HelloService.Iface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one rpcPreprocessor must be specified");

        assertThatThrownBy(() -> ThriftClients
                .newClient("undefined://1.2.3.4", HelloService.Iface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one rpcPreprocessor must be specified");
    }

    @Test
    void httpRequestIsAbortedIfDecoratorThrowException() throws Exception {
        final CompletableFuture<HttpRequest> reqCaptor = new CompletableFuture<>();
        final HelloService.Iface client = ThriftClients.builder("https://google.com/")
                                                       .decorator((delegate, ctx, req) -> {
                                                           reqCaptor.complete(req);
                                                           throw new AnticipatedException();
                                                       })
                                                       .build(HelloService.Iface.class);
        assertThatThrownBy(() -> client.hello("hello")).isInstanceOf(TTransportException.class)
                                                       .cause()
                                                       .isInstanceOf(AnticipatedException.class);
        assertThatThrownBy(() -> reqCaptor.join().whenComplete().join())
                .hasCauseInstanceOf(AbortedStreamException.class);
    }

    @Test
    void serializationFormat() {
        HelloService.Iface client = ThriftClients.builder("https://armeria.dev/")
                                                 .build(HelloService.Iface.class);
        assertThat(Clients.unwrap(client, ClientBuilderParams.class).scheme().serializationFormat())
                .isEqualTo(ThriftSerializationFormats.BINARY);

        for (SerializationFormat format : ThriftSerializationFormats.values()) {
            client = ThriftClients.builder("https://armeria.dev/")
                                  .serializationFormat(format)
                                  .build(HelloService.Iface.class);
            assertThat(Clients.unwrap(client, ClientBuilderParams.class).scheme().serializationFormat())
                    .isEqualTo(format);
        }

        assertThatThrownBy(() -> {
            ThriftClients.builder("https://armeria.dev/")
                         .serializationFormat(SerializationFormat.UNKNOWN);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("serializationFormat: unknown (expected: one of");
    }

    @Test
    void path() {
        final HelloService.Iface client = ThriftClients.builder("https://armeria.dev/")
                                                       .path("/path")
                                                       .build(HelloService.Iface.class);
        assertThat(Clients.unwrap(client, ClientBuilderParams.class).uri().toString())
                .isEqualTo("tbinary+https://armeria.dev/path");
    }

    @Test
    void maxResponseStringLength() {
        final HelloService.Iface client = ThriftClients.builder("https://armeria.dev/")
                                                       .maxResponseStringLength(100)
                                                       .build(HelloService.Iface.class);
        final int maxStringLength = Clients.unwrap(client, ClientBuilderParams.class).options()
                                           .get(ThriftClientOptions.MAX_RESPONSE_STRING_LENGTH);
        assertThat(maxStringLength).isEqualTo(100);

        assertThatThrownBy(() -> {
            ThriftClients.builder("https://armeria.dev/")
                         .maxResponseStringLength(-1);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxResponseStringLength: -1 (expected: >= 0)");
    }

    @Test
    void maxResponseContainerLength() {
        final HelloService.Iface client = ThriftClients.builder("https://armeria.dev/")
                                                       .maxResponseContainerLength(200)
                                                       .build(HelloService.Iface.class);
        final int maxContainerLength = Clients.unwrap(client, ClientBuilderParams.class).options()
                                              .get(ThriftClientOptions.MAX_RESPONSE_CONTAINER_LENGTH);
        assertThat(maxContainerLength).isEqualTo(200);

        assertThatThrownBy(() -> {
            ThriftClients.builder("https://armeria.dev/")
                         .maxResponseContainerLength(-1);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxResponseContainerLength: -1 (expected: >= 0)");
    }

    @Test
    void apiConsistency() {
        final Set<Method> overriddenMethods =
                ReflectionUtils.getMethods(ThriftClientBuilder.class,
                                           method -> Modifier.isPublic(method.getModifiers()) &&
                                                     method.getReturnType().equals(ThriftClientBuilder.class));
        final Set<Method> superMethods =
                ReflectionUtils.getMethods(AbstractClientOptionsBuilder.class,
                                           method -> Modifier.isPublic(method.getModifiers()));
        for (final Method method : superMethods) {
            assertThat(overriddenMethods).filteredOn(tMethod -> {
                return method.getName().equals(tMethod.getName()) &&
                       Arrays.equals(method.getParameterTypes(), tMethod.getParameterTypes());
            }).hasSize(1);
        }
    }

    @Test
    void preprocessorThrows() {
        final HttpPreprocessor preprocessor =
                HttpPreprocessor.of(SessionProtocol.HTTP, Endpoint.of("foo.com"));
        final ThriftClientBuilder builder = ThriftClients.builder("http://foo.com");
        assertThatThrownBy(() -> builder.preprocessor(preprocessor))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("preprocessor() does not support Thrift");

        assertThatThrownBy(() -> Clients.newClient(ThriftSerializationFormats.BINARY,
                                                   ClientPreprocessors.of(preprocessor),
                                                   HelloService.Iface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one rpcPreprocessor must be specified");
    }

    public static Stream<Arguments> preprocessParams_args() {
        return Stream.of(
                Arguments.of(ThriftClients.newClient(PreClient::execute,
                                                     THttpClient.class), "/"),
                Arguments.of(ThriftClients.builder(PreClient::execute)
                                          .path("/prefix")
                                          .build(THttpClient.class), "/prefix")
        );
    }

    @ParameterizedTest
    @MethodSource("preprocessParams_args")
    void preprocessParams(ClientBuilderParams params, String expectedPrefix) {
        assertThat(params.scheme()).isEqualTo(Scheme.of(ThriftSerializationFormats.BINARY,
                                                        SessionProtocol.UNDEFINED));
        assertThat(params.endpointGroup()).isInstanceOf(FailingEndpointGroup.class);
        assertThat(params.absolutePathRef()).isEqualTo(expectedPrefix);
        assertThat(params.uri().getRawAuthority()).startsWith("armeria-preprocessor");
        assertThat(params.uri().getScheme()).isEqualTo("tbinary+undefined");
        assertThat(ClientBuilderParamsUtil.isInternalUri(params.uri())).isTrue();
        assertThat(Clients.isUndefinedUri(params.uri())).isFalse();
    }
}
