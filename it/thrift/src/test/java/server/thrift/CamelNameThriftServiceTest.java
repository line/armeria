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

package server.thrift;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

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
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.SayHelloService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Special test for `fullcamel` option in thrift compiler.
 */
class CamelNameThriftServiceTest {

    private static final String FOO = "foo";

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    private TMemoryInputTransport in;
    private TMemoryBuffer out;

    private CompletableFuture<HttpData> promise;

    @BeforeEach
    void before() {
        in = new TMemoryInputTransport();
        out = new TMemoryBuffer(128);

        promise = new CompletableFuture<>();
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
}
