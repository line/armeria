/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

public class ServerTest extends AbstractServerTest {

    private static final long processDelayMillis = 1000;
    private static final long requestTimeoutMillis = 500;
    private static final long idleTimeoutMillis = 500;

    private static final EventExecutorGroup asyncExecutorGroup = new DefaultEventExecutorGroup(1);

    @Override
    protected void configureServer(ServerBuilder sb) {

        final Service<HttpRequest, HttpResponse> immediateResponseOnIoThread = new EchoService().decorate(LoggingService::new);

        final Service<HttpRequest, HttpResponse> delayedResponseOnIoThread = new EchoService() {
            @Override
            protected void echo(AggregatedHttpMessage aReq, HttpResponseWriter res) {
                try {
                    Thread.sleep(processDelayMillis);
                    super.echo(aReq, res);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.decorate(LoggingService::new);

        final Service<HttpRequest, HttpResponse> lazyResponseNotOnIoThread = new EchoService() {
            @Override
            protected void echo(AggregatedHttpMessage aReq, HttpResponseWriter res) {
                asyncExecutorGroup.schedule(
                        () -> super.echo(aReq, res), processDelayMillis, TimeUnit.MILLISECONDS);
            }
        }.decorate(LoggingService::new);

        final Service<HttpRequest, HttpResponse> buggy = new AbstractHttpService() {
            @Override
            protected void doPost(ServiceRequestContext ctx,
                                  HttpRequest req, HttpResponseWriter res) throws Exception {

                throw Exceptions.clearTrace(new Exception("bug!"));
            }
        }.decorate(LoggingService::new);

        sb.serviceAt("/", immediateResponseOnIoThread)
          .serviceAt("/delayed", delayedResponseOnIoThread)
          .serviceAt("/timeout", lazyResponseNotOnIoThread)
          .serviceAt("/timeout-not", lazyResponseNotOnIoThread)
          .serviceAt("/buggy", buggy);

        // Disable request timeout for '/timeout-not' only.
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator =
                delegate -> new DecoratingService<HttpRequest, HttpResponse, HttpRequest, HttpResponse>(delegate) {
                    @Override
                    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                        ctx.setRequestTimeoutMillis(
                                "/timeout-not".equals(ctx.path()) ? 0 : requestTimeoutMillis);
                        return delegate().serve(ctx, req);
                    }
                };

        sb.decorator(decorator);

        sb.idleTimeoutMillis(idleTimeoutMillis);
    }

    @Test
    public void testStartStop() throws Exception {
        try {
            assertThat(server().activePorts().size(), is(1));
            server().stop().get();
            assertThat(server().activePorts().size(), is(0));
        } finally {
            stopServer();
        }
    }

    @Test
    public void testInvocation() throws Exception {
        testInvocation0("/");
    }

    @Test
    public void testDelayedResponseApiInvocationExpectedTimeout() throws Exception {
        testInvocation0("/delayed");
    }

    private static void testInvocation0(String path) throws IOException {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(uri(path));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Hello, world!"));
            }
        }
    }

    @Test
    public void testRequestTimeoutInvocation() throws Exception {
         try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(uri("/timeout"));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(HttpStatusClass.valueOf(res.getStatusLine().getStatusCode()),
                           is(not(HttpStatusClass.SUCCESS)));
            }
        }
    }

    @Test
    public void testDynamicRequestTimeoutInvocation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(uri("/timeout-not"));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(HttpStatusClass.valueOf(res.getStatusLine().getStatusCode()),
                           is(HttpStatusClass.SUCCESS));
            }
        }
    }

    @Test(timeout = idleTimeoutMillis * 5)
    public void testIdleTimeoutByNoContentSent() throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server().activePort().get().localAddress());
            long connectedNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }
            long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - connectedNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis, is(greaterThanOrEqualTo(idleTimeoutMillis)));
        }
    }

    @Test(timeout = idleTimeoutMillis * 5)
    public void testIdleTimeoutByContentSent() throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server().activePort().get().localAddress());
            PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);
            outWriter.print("POST / HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            long lastWriteNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }

            long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - lastWriteNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis, is(greaterThanOrEqualTo(idleTimeoutMillis)));
        }
    }

    /**
     * Ensure that the connection is not broken even if {@link Service#serve(ServiceRequestContext, Request)}
     * raises an exception.
     */
    @Test(timeout = idleTimeoutMillis * 5)
    public void testBuggyService() throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server().activePort().get().localAddress());
            PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);

            // Send a request to a buggy service whose invoke() raises an exception.
            // If the server handled the exception correctly (i.e. responded with 500 Internal Server Error and
            // recovered from the exception successfully), then the connection should not be closed immediately
            // but on the idle timeout of the second request.
            outWriter.print("POST /buggy HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("Content-Length: 0\r\n");
            outWriter.print("\r\n");
            outWriter.print("POST / HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            long lastWriteNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }

            long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - lastWriteNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis, is(greaterThanOrEqualTo(idleTimeoutMillis)));
        }
    }

    @Test
    public void testOptions() throws Exception {
        testSimple("OPTIONS * HTTP/1.1", "HTTP/1.1 200 OK",
                   "allow: OPTIONS,GET,HEAD,POST,PUT,PATCH,DELETE,TRACE");
    }

    @Test
    public void testInvalidPath() throws Exception {
        testSimple("GET * HTTP/1.1", "HTTP/1.1 400 Bad Request");
    }

    private static void testSimple(
            String reqLine, String expectedStatusLine, String... expectedHeaders) throws Exception {

        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server().activePort().get().localAddress());
            PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);

            outWriter.print(reqLine);
            outWriter.print("\r\n");
            outWriter.print("Connection: close\r\n");
            outWriter.print("Content-Length: 0\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));

            assertThat(in.readLine(), is(expectedStatusLine));
            // Read till the end of the connection.
            List<String> headers = new ArrayList<>();
            for (;;) {
                String line = in.readLine();
                if (line == null) {
                    break;
                }

                // This is not really correct, but just wanna make it as simple as possible.
                headers.add(line);
            }

            for (String expectedHeader : expectedHeaders) {
                if (!headers.contains(expectedHeader)) {
                    fail("does not contain '" + expectedHeader + "': " + headers);
                }
            }
        }
    }

    private static class EchoService extends AbstractHttpService {
        @Override
        protected final void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
            req.aggregate()
               .thenAccept(aReq -> echo(aReq, res))
               .exceptionally(CompletionActions::log);
        }

        protected void echo(AggregatedHttpMessage aReq, HttpResponseWriter res) {
            res.write(HttpHeaders.of(HttpStatus.OK));
            res.write(aReq.content());
            res.close();
        }
    }
}
