/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.it.thrift;

import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;

/**
 * Tests if Armeria decorators can alter the request/response timeout specified in Thrift call parameters.
 */
public class ThriftHttpHeaderTest {

    private static final String SECRET = "QWxhZGRpbjpPcGVuU2VzYW1l";

    private static final HelloService.AsyncIface helloService = (name, resultHandler) -> {
        final ServiceRequestContext ctx = (ServiceRequestContext) RequestContext.current();
        final HttpRequest httpReq = ctx.request();
        final HttpHeaders headers = httpReq.headers();
        if (headers.contains(AUTHORIZATION, SECRET)) {
            resultHandler.onComplete("Hello, " + name + '!');
        } else {
            final String errorMessage;
            if (headers.contains(AUTHORIZATION)) {
                errorMessage = "not authorized: " + headers.get(AUTHORIZATION);
            } else {
                errorMessage = "not authorized due to missing credential";
            }
            resultHandler.onError(new Exception(errorMessage));
        }

        final HttpHeaders responseHeaders = new DefaultHttpHeaders().set(AsciiString.of("foo"), "bar");
        ctx.setAdditionalResponseHeaders(responseHeaders);
    };

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", THttpService.of(helloService));
        }
    };

    @Test
    public void testSimpleManipulation() throws Exception {
        final HelloService.Iface client = newClient();
        try (SafeCloseable ignored = Clients.withHttpHeader(AUTHORIZATION, SECRET)) {
            assertThat(client.hello("trustin")).isEqualTo("Hello, trustin!");
        }

        // Ensure that the header manipulator set in the thread-local variable has been cleared.
        assertAuthorizationFailure(client, null);
    }

    @Test
    public void testNestedManipulation() throws Exception {
        // Split the secret into two pieces.
        final String secretA = SECRET.substring(0, SECRET.length() >>> 1);
        final String secretB = SECRET.substring(secretA.length());

        final HelloService.Iface client = newClient();
        try (SafeCloseable ignored = Clients.withHttpHeader(AUTHORIZATION, secretA)) {
            // Should fail with the first half of the secret.
            assertAuthorizationFailure(client, secretA);
            try (SafeCloseable ignored2 = Clients.withHttpHeaders(
                    h -> h.set(AUTHORIZATION, h.get(AUTHORIZATION) + secretB))) {
                // Should pass if both manipulators worked.
                assertThat(client.hello("foobar")).isEqualTo("Hello, foobar!");
            }
            // Should fail again with the first half of the secret.
            assertAuthorizationFailure(client, secretA);
        }

        // Ensure that the header manipulator set in the thread-local variable has been cleared.
        assertAuthorizationFailure(client, null);
    }

    @Test
    public void testSimpleManipulationAsync() throws Exception {
        final HelloService.AsyncIface client = Clients.newClient(
                server.uri(BINARY, "/hello"), HelloService.AsyncIface.class);

        final BlockingQueue<Object> result = new ArrayBlockingQueue<>(1);
        final Callback callback = new Callback(result);

        try (SafeCloseable ignored = Clients.withHttpHeader(AUTHORIZATION, SECRET)) {
            client.hello("armeria", callback);
        }

        assertThat(result.poll(10, TimeUnit.SECONDS)).isEqualTo("Hello, armeria!");

        // Ensure that the header manipulator set in the thread-local variable has been cleared.
        client.hello("bar", callback);
        assertThat(result.poll(10, TimeUnit.SECONDS))
                .isInstanceOf(TException.class)
                .matches(o -> ((Throwable) o).getMessage().contains("not authorized"),
                         "must fail with authorization failure");
    }

    @Test
    public void testFailedAuthorization() throws Exception {
        assertAuthorizationFailure(newClient(), null);
    }

    @Test
    public void httpResponseHeaderContainsFoo() throws TException {
        final Iface client = new ClientBuilder(server.uri(BINARY, "/hello"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           (delegate, ctx, req) -> {
                               final HttpResponse res = delegate.execute(ctx, req);
                               return new FilteredHttpResponse(res) {
                                   @Override
                                   protected HttpObject filter(HttpObject obj) {
                                       if (obj instanceof HttpHeaders) {
                                           assertThat(((HttpHeaders) obj).get(AsciiString.of("foo")))
                                                   .isEqualTo("bar");
                                       }
                                       return obj;
                                   }
                               };
                           })
                .build(Iface.class);
        try (SafeCloseable ignored = Clients.withHttpHeader(AUTHORIZATION, SECRET)) {
            assertThat(client.hello("trustin")).isEqualTo("Hello, trustin!");
        }
    }

    private static Iface newClient() {
        return Clients.newClient(server.uri(BINARY, "/hello"), HelloService.Iface.class);
    }

    private static void assertAuthorizationFailure(Iface client, String expectedSecret) {
        final String expectedMessage;
        if (expectedSecret != null) {
            expectedMessage = "not authorized: " + expectedSecret;
        } else {
            expectedMessage = "not authorized due to missing credential";
        }
        assertThatThrownBy(() -> client.hello("foo"))
                .isInstanceOf(TException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static final class Callback implements AsyncMethodCallback<String> {

        private final BlockingQueue<Object> result;

        Callback(BlockingQueue<Object> result) {
            this.result = result;
        }

        @Override
        public void onComplete(String response) {
            result.add(response);
        }

        @Override
        public void onError(Exception exception) {
            result.add(exception);
        }
    }
}
