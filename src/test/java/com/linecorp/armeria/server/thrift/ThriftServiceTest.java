/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.DefaultResponseLog;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.FileService;
import com.linecorp.armeria.service.test.thrift.main.FileServiceException;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.Name;
import com.linecorp.armeria.service.test.thrift.main.NameService;
import com.linecorp.armeria.service.test.thrift.main.NameSortService;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;

import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.ImmediateExecutor;

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
@RunWith(Parameterized.class)
public class ThriftServiceTest {

    private static final Name NAME_A = new Name("a", "a", "a");
    private static final Name NAME_B = new Name("b", "b", "b");
    private static final Name NAME_C = new Name("c", "c", "c");

    private static final String FOO = "foo";
    private static final String BAR = "bar";
    private static final String BAZ = "baz";

    @Parameters(name = "{0}")
    public static Collection<Object[]> parameters() throws Exception {
        List<Object[]> parameters = new ArrayList<>();

        parameters.add(new Object[] { SerializationFormat.THRIFT_BINARY });
        parameters.add(new Object[] { SerializationFormat.THRIFT_COMPACT });
        parameters.add(new Object[] { SerializationFormat.THRIFT_JSON });

        return parameters;
    }

    private final SerializationFormat defaultSerializationFormat;

    private TProtocol inProto;
    private TProtocol outProto;
    private TMemoryInputTransport in;
    private TMemoryBuffer out;

    private CompletableFuture<HttpData> promise;
    private CompletableFuture<HttpData> promise2;

    public ThriftServiceTest(SerializationFormat defaultSerializationFormat) {
        this.defaultSerializationFormat = defaultSerializationFormat;
    }

    @Before
    public void before() {
        in = new TMemoryInputTransport();
        out = new TMemoryBuffer(128);
        inProto = ThriftProtocolFactories.get(defaultSerializationFormat).getProtocol(in);
        outProto = ThriftProtocolFactories.get(defaultSerializationFormat).getProtocol(out);

        promise = new CompletableFuture<>();
        promise2 = new CompletableFuture<>();
    }

    @Test
    public void testSync_HelloService_hello() throws Exception {
        HelloService.Client client = new HelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
                (HelloService.Iface) name -> "Hello, " + name + '!', defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_hello(), is("Hello, foo!"));
    }

    @Test
    public void testAsync_HelloService_hello() throws Exception {
        HelloService.Client client = new HelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete("Hello, " + name + '!'), defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_hello(), is("Hello, foo!"));
    }

    @Test
    public void testIdentity_HelloService_hello() throws Exception {
        HelloService.Client client = new HelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService syncService = THttpService.of(
                (HelloService.Iface) name -> "Hello, " + name + '!', defaultSerializationFormat);

        THttpService asyncService = THttpService.of(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete("Hello, " + name + '!'), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_OnewayHelloService_hello() throws Exception {
        final AtomicReference<String> actualName = new AtomicReference<>();

        OnewayHelloService.Client client = new OnewayHelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
                (OnewayHelloService.Iface) actualName::set, defaultSerializationFormat);

        invoke(service);

        assertThat(promise.get().isEmpty(), is(true));
        assertThat(actualName.get(), is(FOO));
    }

    @Test
    public void testAsync_OnewayHelloService_hello() throws Exception {
        final AtomicReference<String> actualName = new AtomicReference<>();

        OnewayHelloService.Client client = new OnewayHelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of((OnewayHelloService.AsyncIface) (name, resultHandler) -> {
            actualName.set(name);
            resultHandler.onComplete(null);
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(promise.get().isEmpty(), is(true));
        assertThat(actualName.get(), is(FOO));
    }

    @Test
    public void testSync_DevNullService_consume() throws Exception {
        final AtomicReference<String> consumed = new AtomicReference<>();

        DevNullService.Client client = new DevNullService.Client.Factory().getClient(inProto, outProto);
        client.send_consume(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
                (DevNullService.Iface) consumed::set, defaultSerializationFormat);

        invoke(service);

        assertThat(consumed.get(), is(FOO));

        client.recv_consume();
    }

    @Test
    public void testAsync_DevNullService_consume() throws Exception {
        final AtomicReference<String> consumed = new AtomicReference<>();

        DevNullService.Client client = new DevNullService.Client.Factory().getClient(inProto, outProto);
        client.send_consume("bar");
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of((DevNullService.AsyncIface) (value, resultHandler) -> {
            consumed.set(value);
            resultHandler.onComplete(null);
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(consumed.get(), is("bar"));

        client.recv_consume();
    }

    @Test
    public void testIdentity_DevNullService_consume() throws Exception {
        DevNullService.Client client = new DevNullService.Client.Factory().getClient(inProto, outProto);
        client.send_consume(FOO);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService syncService = THttpService.of((DevNullService.Iface) value -> {
            // NOOP
        }, defaultSerializationFormat);

        THttpService asyncService = THttpService.of(
                (DevNullService.AsyncIface) (value, resultHandler) ->
                        resultHandler.onComplete(null), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_FileService_create_reply() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAR);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of((FileService.Iface) path -> {
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

    @Test
    public void testAsync_FileService_create_reply() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAR);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
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

    @Test
    public void testIdentity_FileService_create_reply() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAR);
        assertThat(out.length(), is(greaterThan(0)));

        THttpService syncService = THttpService.of((FileService.Iface) path -> {
            throw newFileServiceException();
        }, defaultSerializationFormat);

        THttpService asyncService = THttpService.of(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(newFileServiceException()), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_FileService_create_exception() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAZ);
        assertThat(out.length(), is(greaterThan(0)));

        RuntimeException exception = Exceptions.clearTrace(new RuntimeException());
        THttpService service = THttpService.of((FileService.Iface) path -> {
            throw exception;
        }, defaultSerializationFormat);

        invoke(service);

        try {
            client.recv_create();
            fail(TApplicationException.class.getSimpleName() + " not raised.");
        } catch (TApplicationException e) {
            assertThat(e.getType(), is(TApplicationException.INTERNAL_ERROR));
            assertThat(e.getMessage(), is (exception.toString()));
        }
    }

    @Test
    public void testAsync_FileService_create_exception() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAZ);
        assertThat(out.length(), is(greaterThan(0)));

        RuntimeException exception = Exceptions.clearTrace(new RuntimeException());
        THttpService service = THttpService.of(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(exception), defaultSerializationFormat);

        invoke(service);

        try {
            client.recv_create();
            fail(TApplicationException.class.getSimpleName() + " not raised.");
        } catch (TApplicationException e) {
            assertThat(e.getType(), is(TApplicationException.INTERNAL_ERROR));
            assertThat(e.getMessage(), is (exception.toString()));
        }
    }

    @Test
    public void testIdentity_FileService_create_exception() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAZ);
        assertThat(out.length(), is(greaterThan(0)));

        RuntimeException exception = Exceptions.clearTrace(new RuntimeException());
        THttpService syncService = THttpService.of((FileService.Iface) path -> {
            throw exception;
        }, defaultSerializationFormat);

        THttpService asyncService = THttpService.of(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(exception), defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_NameService_removeMiddle() throws Exception {
        NameService.Client client = new NameService.Client.Factory().getClient(inProto, outProto);
        client.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
                (NameService.Iface) name -> new Name(name.first, null, name.last), defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_removeMiddle(), is(new Name(BAZ, null, FOO)));
    }

    @Test
    public void testAsync_NameService_removeMiddle() throws Exception {
        NameService.Client client = new NameService.Client.Factory().getClient(inProto, outProto);
        client.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of(
                (NameService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(new Name(name.first, null, name.last)),
                defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_removeMiddle(), is(new Name(BAZ, null, FOO)));
    }

    @Test
    public void testIdentity_NameService_removeMiddle() throws Exception {
        NameService.Client client = new NameService.Client.Factory().getClient(inProto, outProto);
        client.send_removeMiddle(new Name(FOO, BAZ, BAR));
        assertThat(out.length(), is(greaterThan(0)));

        THttpService syncService = THttpService.of(
                (NameService.Iface) name -> new Name(name.first, null, name.last), defaultSerializationFormat);

        THttpService asyncService = THttpService.of(
                (NameService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(new Name(name.first, null, name.last)),
                defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_NameSortService_sort() throws Exception {
        NameSortService.Client client = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of((NameSortService.Iface) names -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            return sorted;
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_sort(), is(Arrays.asList(NAME_A, NAME_B, NAME_C)));
    }

    @Test
    public void testAsync_NameSortService_sort() throws Exception {
        NameSortService.Client client = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length(), is(greaterThan(0)));

        THttpService service = THttpService.of((NameSortService.AsyncIface) (names, resultHandler) -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            resultHandler.onComplete(sorted);
        }, defaultSerializationFormat);

        invoke(service);

        assertThat(client.recv_sort(), is(Arrays.asList(NAME_A, NAME_B, NAME_C)));
    }

    @Test
    public void testIdentity_NameSortService_sort() throws Exception {
        NameSortService.Client client = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length(), is(greaterThan(0)));

        THttpService syncService = THttpService.of((NameSortService.Iface) names -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            return sorted;
        }, defaultSerializationFormat);

        THttpService asyncService = THttpService.of((NameSortService.AsyncIface) (names, resultHandler) -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            resultHandler.onComplete(sorted);
        }, defaultSerializationFormat);

        invokeTwice(syncService, asyncService);

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testMultipleInheritance() throws Exception {
        NameService.Client client1 = new NameService.Client.Factory().getClient(inProto, outProto);
        client1.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.length(), is(greaterThan(0)));

        final HttpData req1 = HttpData.of(out.getArray(), 0, out.length());

        out = new TMemoryBuffer(128);
        outProto = ThriftProtocolFactories.get(defaultSerializationFormat).getProtocol(out);

        NameSortService.Client client2 = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client2.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.length(), is(greaterThan(0)));

        final HttpData req2 = HttpData.of(out.getArray(), 0, out.length());

        THttpService service = THttpService.of(new UberNameService(), defaultSerializationFormat);

        invoke(service, req1, promise);
        invoke(service, req2, promise2);

        final HttpData res1 = promise.get();
        final HttpData res2 = promise2.get();

        in.reset(res1.array(), res1.offset(), res1.length());
        assertThat(client1.recv_removeMiddle(), is(new Name(BAZ, null, FOO)));

        in.reset(res2.array(), res2.offset(), res2.length());
        assertThat(client2.recv_sort(), is(Arrays.asList(NAME_A, NAME_B, NAME_C)));
    }

    private static final class UberNameService implements NameService.Iface, NameSortService.AsyncIface {
        @Override
        public Name removeMiddle(Name name) {
            return new Name(name.first, null, name.last);
        }

        @Override
        public void sort(List<Name> names, AsyncMethodCallback resultHandler) {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            resultHandler.onComplete(sorted);
        }
    }

    private void invoke(THttpService service) throws Exception {
        invoke(service, HttpData.of(out.getArray(), 0, out.length()), promise);

        final HttpData res = promise.get();
        in.reset(res.array(), res.offset(), res.length());
    }

    private void invokeTwice(THttpService service1, THttpService service2) throws Exception {
        final HttpData content = HttpData.of(out.getArray(), 0, out.length());
        invoke(service1, content, promise);
        invoke(service2, content, promise2);
    }

    private static void invoke(THttpService service, HttpData content,
                               CompletableFuture<HttpData> promise) throws Exception {

        final ServiceConfig cfg =
                new ServerBuilder().serviceAt("/", service)
                                   .blockingTaskExecutor(ImmediateExecutor.INSTANCE).build()
                                   .config().serviceConfigs().get(0);

        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final DefaultRequestLog reqLogBuilder = new DefaultRequestLog();
        final DefaultResponseLog resLogBuilder = new DefaultResponseLog(reqLogBuilder);

        when(ctx.blockingTaskExecutor()).thenReturn(ImmediateEventExecutor.INSTANCE);
        when(ctx.requestLogBuilder()).thenReturn(reqLogBuilder);
        when(ctx.responseLogBuilder()).thenReturn(resLogBuilder);
        doNothing().when(ctx).invokeOnEnterCallbacks();
        doNothing().when(ctx).invokeOnExitCallbacks();

        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.POST, "/"), false);

        req.write(content);
        req.close();

        final HttpResponse res = service.serve(ctx, req);
        res.aggregate().handle(voidFunction((aReq, cause) -> {
            if (cause == null) {
                if (aReq.headers().status().code() == 200) {
                    promise.complete(aReq.content());
                } else {
                    promise.completeExceptionally(new AssertionError(
                            aReq.headers().status() + ", " +
                            aReq.content().toString(StandardCharsets.UTF_8)));
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
