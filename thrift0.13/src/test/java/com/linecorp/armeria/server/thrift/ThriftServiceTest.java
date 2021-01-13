/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.common.thrift.text.ChildRpcDebugService;
import com.linecorp.armeria.common.thrift.text.Response;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.service.test.thrift.main.BinaryService;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.FileService;
import com.linecorp.armeria.service.test.thrift.main.FileServiceException;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.Name;
import com.linecorp.armeria.service.test.thrift.main.NameService;
import com.linecorp.armeria.service.test.thrift.main.NameSortService;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;
import com.linecorp.armeria.service.test.thrift.main.SayHelloService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Tests {@link ThriftCallService} and {@link THttpService}.
 * <p>
 * The test methods have the following naming convention:
 *
 * <pre><i>TestType</i>_<i>ServiceName</i>_<i>MethodName</i>[_<i>AdditionalInfo</i>]()</pre>
 *
 * .. where each field has the following meaning:
 * <ul>
 *     <li>{@code TestType}
 *     <ul>
 *         <li>{@code Sync} tests an invocation of synchronous service. i.e. {@code *.Iface}</li>
 *         <li>{@code Async} tests an invocation of asynchronous service. i.e. {@code *.AsyncIface}</li>
 *         <li>{@code Identity} tests if the results of synchronous and asynchronous operations are identical
 *             at protocol level.</li>
 *         <li>{@code MultipleInheritance} tests the case where a service implementation implements
 *             multiple interfaces.</li>
 *     </ul></li>
 *     <li>{@code ServiceName} - the class name of the service being tested</li>
 *     <li>{@code MethodName} - the name of the method in the service being tested</li>
 *     <li>(Optional) {@code AdditionalInfo} - specified when a service method has more than one test case</li>
 * </ul>
 * </p>
 */
class ThriftServiceTest {

    private static final Name NAME_A = new Name("a", "a", "a");
    private static final Name NAME_B = new Name("b", "b", "b");
    private static final Name NAME_C = new Name("c", "c", "c");

    private static final String FOO = "foo";
    private static final String BAR = "bar";
    private static final String BAZ = "baz";

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    private TMemoryInputTransport in;
    private TMemoryBuffer out;

    private CompletableFuture<HttpData> promise;
    private CompletableFuture<HttpData> promise2;

    @BeforeEach
    void before() {
        in = new TMemoryInputTransport();
        out = new TMemoryBuffer(128);

        promise = new CompletableFuture<>();
        promise2 = new CompletableFuture<>();
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_HelloService_hello(SerializationFormat defaultSerializationFormat) throws Exception {
        final HelloService.Client client = new HelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (HelloService.Iface) name -> "Hello, " + name + '!', defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_hello()).isEqualTo("Hello, foo!");
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_SayHelloService_sayHello(SerializationFormat defaultSerializationFormat) throws Exception {
        final SayHelloService.Client client = new SayHelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.sendSayHello(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (SayHelloService.Iface) name -> "Hello, " + name + '!', defaultSerializationFormat);

        invoke(service);

        assertThat(client.recvSayHello()).isEqualTo("Hello, foo!");
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_HelloService_hello(SerializationFormat defaultSerializationFormat) throws Exception {
        final HelloService.Client client = new HelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete("Hello, " + name + '!'), defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_hello()).isEqualTo("Hello, foo!");
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_HelloService_hello_with_null(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final HelloService.Client client = new HelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(null);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (HelloService.Iface) name -> String.valueOf(name != null), defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_hello()).isEqualTo("false");
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_HelloService_hello_with_null(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final HelloService.Client client = new HelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(null);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(String.valueOf(name != null)), defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_hello()).isEqualTo("false");
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testIdentity_HelloService_hello(SerializationFormat defaultSerializationFormat) throws Exception {
        final HelloService.Client client = new HelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService syncService = THttpService.of(
                (HelloService.Iface) name -> "Hello, " + name + '!', defaultSerializationFormat);

        final THttpService asyncService = THttpService.of(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete("Hello, " + name + '!'), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get()).isEqualTo(promise2.get());
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_OnewayHelloService_hello(SerializationFormat defaultSerializationFormat) throws Exception {
        final AtomicReference<String> actualName = new AtomicReference<>();

        final OnewayHelloService.Client client =
                new OnewayHelloService.Client.Factory().getClient(
                        inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (OnewayHelloService.Iface) actualName::set, defaultSerializationFormat);

        invoke(service);

        assertThat(promise.get().isEmpty()).isTrue();
        assertThat(actualName.get()).isEqualTo(FOO);
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_OnewayHelloService_hello(SerializationFormat defaultSerializationFormat) throws Exception {
        final AtomicReference<String> actualName = new AtomicReference<>();

        final OnewayHelloService.Client client =
                new OnewayHelloService.Client.Factory().getClient(
                        inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_hello(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of((OnewayHelloService.AsyncIface) (name, resultHandler) -> {
            actualName.set(name);
            resultHandler.onComplete(null);
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(promise.get().isEmpty()).isTrue();
        assertThat(actualName.get()).isEqualTo(FOO);
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_DevNullService_consume(SerializationFormat defaultSerializationFormat) throws Exception {
        final AtomicReference<String> consumed = new AtomicReference<>();

        final DevNullService.Client client = new DevNullService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_consume(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (DevNullService.Iface) consumed::set, defaultSerializationFormat);

        invoke(service);

        assertThat(consumed.get()).isEqualTo(FOO);

        client.recv_consume();
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_DevNullService_consume(SerializationFormat defaultSerializationFormat) throws Exception {
        final AtomicReference<String> consumed = new AtomicReference<>();

        final DevNullService.Client client = new DevNullService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_consume("bar");
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of((DevNullService.AsyncIface) (value, resultHandler) -> {
            consumed.set(value);
            resultHandler.onComplete(null);
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(consumed.get()).isEqualTo("bar");

        client.recv_consume();
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testIdentity_DevNullService_consume(SerializationFormat defaultSerializationFormat) throws Exception {
        final DevNullService.Client client = new DevNullService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_consume(FOO);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService syncService = THttpService.of((DevNullService.Iface) value -> {
            // NOOP
        }, defaultSerializationFormat);

        final THttpService asyncService = THttpService.of(
                (DevNullService.AsyncIface) (value, resultHandler) ->
                        resultHandler.onComplete(null), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get()).isEqualTo(promise2.get());
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_FileService_create_reply(SerializationFormat defaultSerializationFormat) throws Exception {
        final FileService.Client client = new FileService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_create(BAR);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of((FileService.Iface) path -> {
            throw newFileServiceException();
        }, defaultSerializationFormat);

        invoke(service);

        try {
            client.recv_create();
            fail(FileServiceException.class.getSimpleName() + " not raised.");
        } catch (FileServiceException ignored) {
            // Expected
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_FileService_create_reply(SerializationFormat defaultSerializationFormat) throws Exception {
        final FileService.Client client = new FileService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_create(BAR);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(newFileServiceException()), defaultSerializationFormat);

        invoke(service);

        try {
            client.recv_create();
            fail(FileServiceException.class.getSimpleName() + " not raised.");
        } catch (FileServiceException ignored) {
            // Expected
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testIdentity_FileService_create_reply(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final FileService.Client client = new FileService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_create(BAR);
        assertThat(out.length()).isGreaterThan(0);

        final THttpService syncService = THttpService.of((FileService.Iface) path -> {
            throw newFileServiceException();
        }, defaultSerializationFormat);

        final THttpService asyncService =
                THttpService.builder()
                            .addService(
                                    (FileService.AsyncIface) (path, resultHandler) ->
                                            resultHandler.onError(newFileServiceException()))
                            .defaultSerializationFormat(defaultSerializationFormat)
                            .build();

        invokeTwice(syncService, asyncService);

        assertThat(promise.get()).isEqualTo(promise2.get());
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_FileService_create_exception(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final FileService.Client client = new FileService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_create(BAZ);
        assertThat(out.length()).isGreaterThan(0);

        final RuntimeException exception = Exceptions.clearTrace(new RuntimeException());
        final THttpService service = THttpService.of((FileService.Iface) path -> {
            throw exception;
        }, defaultSerializationFormat);

        invoke(service);

        try {
            client.recv_create();
            fail(TApplicationException.class.getSimpleName() + " not raised.");
        } catch (TApplicationException e) {
            assertThat(e.getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
            assertThat(e.getMessage()).contains(exception.toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_FileService_create_exception(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final FileService.Client client = new FileService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_create(BAZ);
        assertThat(out.length()).isGreaterThan(0);

        final RuntimeException exception = Exceptions.clearTrace(new RuntimeException());
        final THttpService service = THttpService.of(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(exception), defaultSerializationFormat);

        invoke(service);

        try {
            client.recv_create();
            fail(TApplicationException.class.getSimpleName() + " not raised.");
        } catch (TApplicationException e) {
            assertThat(e.getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
            assertThat(e.getMessage()).contains(exception.toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testIdentity_FileService_create_exception(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final FileService.Client client = new FileService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_create(BAZ);
        assertThat(out.length()).isGreaterThan(0);

        final RuntimeException exception = Exceptions.clearTrace(new RuntimeException());
        final THttpService syncService = THttpService.of((FileService.Iface) path -> {
            throw exception;
        }, defaultSerializationFormat);

        final THttpService asyncService = THttpService.of(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(exception), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get()).isEqualTo(promise2.get());
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_NameService_removeMiddle(SerializationFormat defaultSerializationFormat) throws Exception {
        final NameService.Client client = new NameService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (NameService.Iface) name -> new Name(name.first, null, name.last), defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_removeMiddle()).isEqualTo(new Name(BAZ, null, FOO));
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_NameService_removeMiddle(SerializationFormat defaultSerializationFormat) throws Exception {
        final NameService.Client client = new NameService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of(
                (NameService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(new Name(name.first, null, name.last)),
                defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_removeMiddle()).isEqualTo(new Name(BAZ, null, FOO));
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testIdentity_NameService_removeMiddle(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final NameService.Client client = new NameService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_removeMiddle(new Name(FOO, BAZ, BAR));
        assertThat(out.length()).isGreaterThan(0);

        final THttpService syncService = THttpService.of(
                (NameService.Iface) name -> new Name(name.first, null, name.last), defaultSerializationFormat);

        final THttpService asyncService = THttpService.of(
                (NameService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(new Name(name.first, null, name.last)),
                defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get()).isEqualTo(promise2.get());
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_NameSortService_sort(SerializationFormat defaultSerializationFormat) throws Exception {
        final NameSortService.Client client = new NameSortService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of((NameSortService.Iface) names -> {
            final ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            return sorted;
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_sort()).containsExactly(NAME_A, NAME_B, NAME_C);
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testAsync_NameSortService_sort(SerializationFormat defaultSerializationFormat) throws Exception {
        final NameSortService.Client client = new NameSortService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length()).isGreaterThan(0);

        final THttpService service = THttpService.of((NameSortService.AsyncIface) (names, resultHandler) -> {
            final ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            resultHandler.onComplete(sorted);
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_sort()).containsExactly(NAME_A, NAME_B, NAME_C);
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testIdentity_NameSortService_sort(SerializationFormat defaultSerializationFormat) throws Exception {
        final NameSortService.Client client = new NameSortService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length()).isGreaterThan(0);

        final THttpService syncService = THttpService.of((NameSortService.Iface) names -> {
            final ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            return sorted;
        }, defaultSerializationFormat);

        final THttpService asyncService =
                THttpService.of((NameSortService.AsyncIface) (names, resultHandler) -> {
                    final ArrayList<Name> sorted = new ArrayList<>(names);
                    Collections.sort(sorted);
                    resultHandler.onComplete(sorted);
                }, defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get()).isEqualTo(promise2.get());
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testBinary(SerializationFormat defaultSerializationFormat) throws Exception {
        final BinaryService.Client client = new BinaryService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client.send_process(ByteBuffer.wrap(new byte[] { 1, 2 }));

        final THttpService service = THttpService.of((BinaryService.Iface) data -> {
            final ByteBuffer result = ByteBuffer.allocate(data.remaining());
            for (int i = data.position(), j = 0; i < data.limit(); i++, j++) {
                result.put(j, (byte) (data.get(i) + 1));
            }
            return result;
        }, defaultSerializationFormat);

        invoke(service);

        final ByteBuffer result = client.recv_process();

        // Convert the result into a Byte[] for more comprehensive comparison.
        final List<Byte> out = new ArrayList<>();
        for (int i = result.position(); i < result.limit(); i++) {
            out.add(result.get(i));
        }

        assertThat(out).contains((byte) 2, (byte) 3);
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testMultipleInheritance(SerializationFormat defaultSerializationFormat) throws Exception {
        final NameService.Client client1 = new NameService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client1.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length()).isGreaterThan(0);

        final HttpData req1 = HttpData.wrap(out.getArray(), 0, out.length());

        out = new TMemoryBuffer(128);
        final TProtocol outProto = outProto(defaultSerializationFormat);

        final NameSortService.Client client2 =
                new NameSortService.Client.Factory().getClient(
                        inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        client2.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length()).isGreaterThan(0);

        final HttpData req2 = HttpData.wrap(out.getArray(), 0, out.length());

        final THttpService service = THttpService.of(
                (UberNameService) (names, callback) -> callback.onComplete(
                        names.stream().sorted().collect(toImmutableList())),
                defaultSerializationFormat);

        invoke0(service, req1, promise);
        invoke0(service, req2, promise2);

        final HttpData res1 = promise.get();
        final HttpData res2 = promise2.get();

        in.reset(res1.array());
        assertThat(client1.recv_removeMiddle()).isEqualTo(new Name(BAZ, null, FOO));

        in.reset(res2.array());
        assertThat(client2.recv_sort()).containsExactly(NAME_A, NAME_B, NAME_C);
    }

    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testServiceInheritance(SerializationFormat defaultSerializationFormat) {
        // This should not throw an exception
        THttpService.of((ChildRpcDebugService.Iface) (a1, a2, details) -> new Response("asdf"));
    }

    /**
     * This tests when multiple thrift service implementations, with same service method are added under
     * default("") service name then the thrift service that is added first should handle the request.
     * And also makes sure that adding a duplicate service does not break other services.
     */
    @ParameterizedTest
    @ArgumentsSource(SerializationFormatProvider.class)
    void testSync_HelloService_hello_WhenSameMethodMultipleTimes(SerializationFormat defaultSerializationFormat)
            throws Exception {
        final HelloService.Client helloClient = new HelloService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        helloClient.send_hello(FOO);
        assertThat(out.length()).isGreaterThan(0);
        final HttpData req1 = HttpData.wrap(out.getArray(), 0, out.length());

        out = new TMemoryBuffer(128);
        final NameService.Client nameClient = new NameService.Client.Factory().getClient(
                inProto(defaultSerializationFormat), outProto(defaultSerializationFormat));
        nameClient.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length()).isGreaterThan(0);
        final HttpData req2 = HttpData.wrap(out.getArray(), 0, out.length());

        final THttpService service =
                THttpService.builder()
                            .addService((HelloService.Iface) name -> "Hello, " + name + '!')
                            // Duplicate service with same method name
                            .addService((HelloService.Iface) name -> "Hello (again) " + name + '!')
                            .addService((NameService.Iface) name -> new Name(name.first, null, name.last))
                            .defaultSerializationFormat(defaultSerializationFormat)
                            .build();

        invoke0(service, req1, promise);
        invoke0(service, req2, promise2);

        final HttpData res1 = promise.get();
        final HttpData res2 = promise2.get();

        in.reset(res1.array());
        assertThat(helloClient.recv_hello()).isEqualTo("Hello, foo!");

        in.reset(res2.array());
        assertThat(nameClient.recv_removeMiddle()).isEqualTo(new Name(BAZ, null, FOO));
    }

    // NB: By making this interface functional, we can use lambda expression to implement
    //     NameSortService.AsyncIface.sort(). By using lambda expression, we can omit the parameter type
    //     declarations (i.e. no AsyncMethodCallback<List<Name>>). By omitting the parameter type declaration,
    //     We can compile it with both Thrift 0.9 (which uses raw type for AsyncMethodCallback) and
    //     0.10 (which uses a concrete type parameter for AsyncMethodCallback.)
    @FunctionalInterface
    private interface UberNameService extends NameService.Iface, NameSortService.AsyncIface {
        @Override
        default Name removeMiddle(Name name) {
            return new Name(name.first, null, name.last);
        }
    }

    private TProtocol inProto(SerializationFormat defaultSerializationFormat) {
        return ThriftSerializationFormats.protocolFactory(defaultSerializationFormat).getProtocol(in);
    }

    private TProtocol outProto(SerializationFormat defaultSerializationFormat) {
        return ThriftSerializationFormats.protocolFactory(defaultSerializationFormat).getProtocol(out);
    }

    private static class SerializationFormatProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return ThriftSerializationFormats.values().stream().map(Arguments::of);
        }
    }

    private void invoke(THttpService service) throws Exception {
        invoke0(service, HttpData.wrap(out.getArray(), 0, out.length()), promise);

        final HttpData res = promise.get();
        in.reset(res.array());
    }

    private void invokeTwice(THttpService service1, THttpService service2) throws Exception {
        final HttpData content = HttpData.wrap(out.getArray(), 0, out.length());
        invoke0(service1, content, promise);
        invoke0(service2, content, promise2);
    }

    private static void invoke0(THttpService service, HttpData content,
                                CompletableFuture<HttpData> promise) throws Exception {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, "/");
        req.write(content);
        req.close();

        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(req)
                                     .eventLoop(eventLoop.get())
                                     .serverConfigurator(builder -> {
                                         builder.blockingTaskExecutor(ImmediateEventExecutor.INSTANCE, false);
                                         builder.verboseResponses(true);
                                     })
                                     .build();

        final HttpResponse res = service.serve(ctx, req);
        res.aggregate().handle(voidFunction((aReq, cause) -> {
            if (cause == null) {
                if (aReq.status().code() == 200) {
                    promise.complete(aReq.content());
                } else {
                    promise.completeExceptionally(
                            new AssertionError(aReq.status() + ", " + aReq.contentUtf8()));
                }
            } else {
                promise.completeExceptionally(cause);
            }
        })).exceptionally(CompletionActions::log);
    }

    @Nonnull
    private static FileServiceException newFileServiceException() {
        // Remove the stack trace so we do not pollute the build log.
        return Exceptions.clearTrace(new FileServiceException());
    }
}
