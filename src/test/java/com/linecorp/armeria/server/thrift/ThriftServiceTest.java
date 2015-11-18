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

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.ServiceCodec.DecodeResult;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.FileService;
import com.linecorp.armeria.service.test.thrift.main.FileServiceException;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.Name;
import com.linecorp.armeria.service.test.thrift.main.NameService;
import com.linecorp.armeria.service.test.thrift.main.NameSortService;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * Tests {@link ThriftService}.
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

    private static final Channel CH = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    private static final SessionProtocol PROTO = SessionProtocol.HTTP;
    private static final String HOST = "localhost";
    private static final String PATH = "/service";

    private static final Name NAME_A = new Name("a", "a", "a");
    private static final Name NAME_B = new Name("b", "b", "b");
    private static final Name NAME_C = new Name("c", "c", "c");

    private static final String FOO = "foo";
    private static final String BAR = "bar";
    private static final String BAZ = "baz";

    private static final TByteBufTransport inTransport = new TByteBufTransport();
    private static final TByteBufTransport outTransport = new TByteBufTransport();

    private static final ByteBuf in = Unpooled.buffer();
    private static final ByteBuf out = Unpooled.buffer();

    @Parameters(name = "{0}")
    public static Collection<Object[]> parameters() throws Exception {
        List<Object[]> parameters = new ArrayList<>();

        parameters.add(new Object[] { ThriftProtocolFactories.BINARY });
        parameters.add(new Object[] { ThriftProtocolFactories.COMPACT });
        parameters.add(new Object[] { ThriftProtocolFactories.JSON });

        return parameters;
    }

    private final TProtocolFactory protoFactory;
    private final TProtocol inProto;
    private final TProtocol outProto;

    private Promise<ByteBuf> promise;
    private Promise<ByteBuf> promise2;

    public ThriftServiceTest(TProtocolFactory protoFactory) {
        this.protoFactory = protoFactory;
        inProto = protoFactory.getProtocol(inTransport);
        outProto = protoFactory.getProtocol(outTransport);
    }

    @BeforeClass
    public static void beforeClass() {
        inTransport.reset(in);
        outTransport.reset(out);
    }

    @Before
    public void before() {
        in.clear();
        out.clear();
        promise = ImmediateEventExecutor.INSTANCE.newPromise();
        promise2 = ImmediateEventExecutor.INSTANCE.newPromise();
    }

    @AfterClass
    public static void afterClass() {
        in.release();
        out.release();
    }

    @Test
    public void testSync_HelloService_hello() throws Exception {
        HelloService.Client client = new HelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (HelloService.Iface) name -> "Hello, " + name + '!', protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(client.recv_hello(), is("Hello, foo!"));
    }

    @Test
    public void testAsync_HelloService_hello() throws Exception {
        HelloService.Client client = new HelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete("Hello, " + name + '!'), protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(client.recv_hello(), is("Hello, foo!"));
    }

    @Test
    public void testIdentity_HelloService_hello() throws Exception {
        HelloService.Client client = new HelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService syncService = new ThriftService(
                (HelloService.Iface) name -> "Hello, " + name + '!', protoFactory);

        ThriftService asyncService = new ThriftService(
                (HelloService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete("Hello, " + name + '!'), protoFactory);

        invoke(syncService, CH, PROTO, HOST, PATH, out.duplicate(), promise);
        invoke(asyncService, CH, PROTO, HOST, PATH, out.duplicate(), promise2);
        promise.sync();
        promise2.sync();

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_OnewayHelloService_hello() throws Exception {
        final AtomicReference<String> actualName = new AtomicReference<>();

        OnewayHelloService.Client client = new OnewayHelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (OnewayHelloService.Iface) actualName::set, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        assertThat(promise.get(), is(nullValue()));
        assertThat(actualName.get(), is(FOO));
    }

    @Test
    public void testAsync_OnewayHelloService_hello() throws Exception {
        final AtomicReference<String> actualName = new AtomicReference<>();

        OnewayHelloService.Client client = new OnewayHelloService.Client.Factory().getClient(inProto, outProto);
        client.send_hello(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService((OnewayHelloService.AsyncIface) (name, resultHandler) -> {
            actualName.set(name);
            resultHandler.onComplete(null);
        }, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        assertThat(promise.get(), is(nullValue()));
        assertThat(actualName.get(), is(FOO));
    }

    @Test
    public void testSync_DevNullService_consume() throws Exception {
        final AtomicReference<String> consumed = new AtomicReference<>();

        DevNullService.Client client = new DevNullService.Client.Factory().getClient(inProto, outProto);
        client.send_consume(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (DevNullService.Iface) consumed::set, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(consumed.get(), is(FOO));

        client.recv_consume();
    }

    @Test
    public void testAsync_DevNullService_consume() throws Exception {
        final AtomicReference<String> consumed = new AtomicReference<>();

        DevNullService.Client client = new DevNullService.Client.Factory().getClient(inProto, outProto);
        client.send_consume("bar");
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService((DevNullService.AsyncIface) (value, resultHandler) -> {
            consumed.set(value);
            resultHandler.onComplete(null);
        }, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(consumed.get(), is("bar"));

        client.recv_consume();
    }

    @Test
    public void testIdentity_DevNullService_consume() throws Exception {
        DevNullService.Client client = new DevNullService.Client.Factory().getClient(inProto, outProto);
        client.send_consume(FOO);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService syncService = new ThriftService((DevNullService.Iface) value -> {
            // NOOP
        }, protoFactory);

        ThriftService asyncService = new ThriftService(
                (DevNullService.AsyncIface) (value, resultHandler) ->
                        resultHandler.onComplete(null), protoFactory);

        invoke(syncService, CH, PROTO, HOST, PATH, out.duplicate(), promise);
        invoke(asyncService, CH, PROTO, HOST, PATH, out.duplicate(), promise2);
        promise.sync();
        promise2.sync();

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_FileService_create_reply() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAR);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService((FileService.Iface) path -> {
            throw new FileServiceException();
        }, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

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
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(new FileServiceException()), protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

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
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService syncService = new ThriftService((FileService.Iface) path -> {
            throw new FileServiceException();
        }, protoFactory);

        ThriftService asyncService = new ThriftService(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(new FileServiceException()), protoFactory);

        invoke(syncService, CH, PROTO, HOST, PATH, out.duplicate(), promise);
        invoke(asyncService, CH, PROTO, HOST, PATH, out.duplicate(), promise2);
        promise.sync();
        promise2.sync();

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_FileService_create_exception() throws Exception {
        FileService.Client client = new FileService.Client.Factory().getClient(inProto, outProto);
        client.send_create(BAZ);
        assertThat(out.readableBytes(), is(greaterThan(0)));

        RuntimeException exception = new RuntimeException();
        ThriftService service = new ThriftService((FileService.Iface) path -> {
            throw exception;
        }, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

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
        assertThat(out.readableBytes(), is(greaterThan(0)));

        RuntimeException exception = new RuntimeException();
        ThriftService service = new ThriftService(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(exception), protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

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
        assertThat(out.readableBytes(), is(greaterThan(0)));

        RuntimeException exception = new RuntimeException();
        ThriftService syncService = new ThriftService((FileService.Iface) path -> {
            throw exception;
        }, protoFactory);

        ThriftService asyncService = new ThriftService(
                (FileService.AsyncIface) (path, resultHandler) ->
                        resultHandler.onError(exception), protoFactory);

        invoke(syncService, CH, PROTO, HOST, PATH, out.duplicate(), promise);
        invoke(asyncService, CH, PROTO, HOST, PATH, out.duplicate(), promise2);
        promise.sync();
        promise2.sync();

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_NameService_removeMiddle() throws Exception {
        NameService.Client client = new NameService.Client.Factory().getClient(inProto, outProto);
        client.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (NameService.Iface) name -> new Name(name.first, null, name.last), protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(client.recv_removeMiddle(), is(new Name(BAZ, null, FOO)));
    }

    @Test
    public void testAsync_NameService_removeMiddle() throws Exception {
        NameService.Client client = new NameService.Client.Factory().getClient(inProto, outProto);
        client.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService(
                (NameService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(new Name(name.first, null, name.last)), protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(client.recv_removeMiddle(), is(new Name(BAZ, null, FOO)));
    }

    @Test
    public void testIdentity_NameService_removeMiddle() throws Exception {
        NameService.Client client = new NameService.Client.Factory().getClient(inProto, outProto);
        client.send_removeMiddle(new Name(FOO, BAZ, BAR));
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService syncService = new ThriftService(
                (NameService.Iface) name -> new Name(name.first, null, name.last), protoFactory);

        ThriftService asyncService = new ThriftService(
                (NameService.AsyncIface) (name, resultHandler) ->
                        resultHandler.onComplete(new Name(name.first, null, name.last)), protoFactory);

        invoke(syncService, CH, PROTO, HOST, PATH, out.duplicate(), promise);
        invoke(asyncService, CH, PROTO, HOST, PATH, out.duplicate(), promise2);
        promise.sync();
        promise2.sync();

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testSync_NameSortService_sort() throws Exception {
        NameSortService.Client client = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService((NameSortService.Iface) names -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            return sorted;
        }, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(client.recv_sort(), is(Arrays.asList(NAME_A, NAME_B, NAME_C)));
    }

    @Test
    public void testAsync_NameSortService_sort() throws Exception {
        NameSortService.Client client = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService service = new ThriftService((NameSortService.AsyncIface) (names, resultHandler) -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            resultHandler.onComplete(sorted);
        }, protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out, promise);
        promise.sync();

        in.writeBytes(promise.get());

        assertThat(client.recv_sort(), is(Arrays.asList(NAME_A, NAME_B, NAME_C)));
    }

    @Test
    public void testIdentity_NameSortService_sort() throws Exception {
        NameSortService.Client client = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.readableBytes(), is(greaterThan(0)));

        ThriftService syncService = new ThriftService((NameSortService.Iface) names -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            return sorted;
        }, protoFactory);

        ThriftService asyncService = new ThriftService((NameSortService.AsyncIface) (names, resultHandler) -> {
            ArrayList<Name> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            resultHandler.onComplete(sorted);
        }, protoFactory);

        invoke(syncService, CH, PROTO, HOST, PATH, out.duplicate(), promise);
        invoke(asyncService, CH, PROTO, HOST, PATH, out.duplicate(), promise2);
        promise.sync();
        promise2.sync();

        assertThat(promise.get(), is(promise2.get()));
    }

    @Test
    public void testMultipleInheritance() throws Exception {
        NameService.Client client1 = new NameService.Client.Factory().getClient(inProto, outProto);
        client1.send_removeMiddle(new Name(BAZ, BAR, FOO));
        assertThat(out.readableBytes(), is(greaterThan(0)));
        ByteBuf out1 = out.copy();
        out.clear();

        NameSortService.Client client2 = new NameSortService.Client.Factory().getClient(inProto, outProto);
        client2.send_sort(Arrays.asList(NAME_C, NAME_B, NAME_A));
        assertThat(out.readableBytes(), is(greaterThan(0)));
        ByteBuf out2 = out.copy();
        out.clear();

        ThriftService service = new ThriftService(new UberNameService(), protoFactory);

        invoke(service, CH, PROTO, HOST, PATH, out1, promise);
        invoke(service, CH, PROTO, HOST, PATH, out2, promise2);
        promise.sync();
        promise2.sync();

        in.writeBytes(promise.get());
        assertThat(client1.recv_removeMiddle(), is(new Name(BAZ, null, FOO)));

        in.writeBytes(promise2.get());
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

    private static void invoke(ThriftService service,
                               Channel ch, SessionProtocol protocol, String hostname, String path,
                               ByteBuf in, Promise<ByteBuf> promise) throws Exception {

        final ServiceCodec codec = service.codec();
        final Promise<Object> objPromise = ch.eventLoop().newPromise();
        final DecodeResult result = codec.decodeRequest(
                ch, protocol, hostname, path, path, in, null, objPromise);

        switch (result.type()) {
        case SUCCESS:
            final ServiceInvocationContext ctx = result.invocationContext();
            service.handler().invoke(ctx, GlobalEventExecutor.INSTANCE, objPromise);
            objPromise.addListener((Future<Object> future) -> {
                if (future.isSuccess()) {
                    promise.setSuccess(codec.encodeResponse(ctx, future.getNow()));
                } else {
                    promise.setSuccess(codec.encodeFailureResponse(ctx, future.cause()));
                }
            });
            break;
        case FAILURE:
            promise.setSuccess((ByteBuf) result.errorResponse());
            break;
        default:
            promise.setFailure(new IllegalStateException("unexpected decode result type: " + result.type()));
        }
    }
}
