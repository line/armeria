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

package com.linecorp.armeria.server.jetty;

import static com.linecorp.armeria.server.jetty.JettyServiceTestUtil.newJettyService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JettyServiceTest extends WebAppContainerTest {

    private static final List<Object> jettyBeans = new ArrayList<>();

    /**
     * Indicates no exceptions were captured in {@link #capturedException}.
     */
    private static final Exception NO_EXCEPTION = new Exception();

    /**
     * Captures the exception raised in a Jetty handler block.
     */
    private static final AtomicReference<Throwable> capturedException = new AtomicReference<>();
    private static final Exception RUNTIME_EXCEPTION = new RuntimeException("RUNTIME_EXCEPTION");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.requestTimeoutMillis(0);

            sb.serviceUnder(
                    "/jsp/",
                    JettyService.builder()
                                .handler(newWebAppContext())
                                .customizer(s -> jettyBeans.addAll(s.getBeans()))
                                .build()
                                .decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/default/",
                    JettyService.builder()
                                .handler(new DefaultHandler())
                                .build());

            final ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setBaseResourceAsString(webAppRoot().getPath());
            sb.serviceUnder(
                    "/resources/",
                    JettyService.builder()
                                .handler(resourceHandler)
                                .build());

            sb.service(
                    "/headers-only",
                    newJettyService((req, res, callback) -> {
                        res.setStatus(204);
                        res.getHeaders().add("x-headers", "foo");
                        callback.succeeded();
                        return true;
                    }));

            sb.service(
                    "/headers-trailers",
                    newJettyService((req, res, callback) -> {
                        res.setStatus(200);
                        res.getHeaders().add("x-headers", "bar");
                        res.setTrailersSupplier(() -> HttpFields.from(new HttpField("x-trailers", "baz")));
                        callback.succeeded();
                        return true;
                    }));

            sb.service("/headers-data-trailers",
                       newJettyService((req, res, callback) -> {
                           res.setStatus(200);
                           res.getHeaders().add("x-headers", "bar");
                           res.setTrailersSupplier(() -> HttpFields.from(new HttpField("x-trailers", "baz")));
                           res.write(true, ByteBuffer.wrap("qux".getBytes()), callback);
                           return true;
                       }));

            // Attempts to write after the request handling is completed due to timeout or disconnection,
            // and captures the exception raised by {@link ServletOutputStream#println()}.
            sb.service("/timeout/{timeout}",
                       newJettyService((req, res, callback) -> {
                           final ServiceRequestContext ctx = ServiceRequestContext.current();
                           ctx.setRequestTimeoutMillis(Integer.parseInt(ctx.pathParam("timeout")));
                           res.setStatus(200);
                           await().until(() -> ctx.log().isComplete());
                           try {
                               res.write(true, ByteBuffer.wrap(System.lineSeparator().getBytes()), callback);
                               capturedException.set(NO_EXCEPTION);
                           } catch (Throwable cause) {
                               capturedException.set(cause);
                           }
                           return true;
                       }));

            // Attempts to write again after closing the output stream,
            // and captures the exception raised by the failed write attempt after close().
            sb.service("/write-after-completion",
                       newJettyService((req, res, callback) -> {
                           res.setStatus(200);
                           res.write(true, ByteBuffer.wrap("before close".getBytes()), callback);
                           res.write(true, ByteBuffer.wrap("after close".getBytes()), new Callback() {
                               @Override
                               public void succeeded() {
                                   capturedException.set(NO_EXCEPTION);
                               }

                               @Override
                               public void failed(Throwable x) {
                                   capturedException.set(x);
                               }
                           });
                           return true;
                       }));

            sb.service("/stream/{totalSize}/{chunkSize}",
                       newJettyService(new AsyncStreamingHandlerFunction()));

            sb.service("/throwing",
                       newJettyService((req, res, callback) -> {
                           callback.succeeded();
                           return true;
                       }).decorate((delegate, ctx, req) -> {
                           ctx = spy(ctx);
                           // relies on the fact that JettyService calls this method
                           when(ctx.sessionProtocol()).thenThrow(RUNTIME_EXCEPTION);
                           return delegate.serve(ctx, req);
                       }));
        }
    };

    static WebAppContext newWebAppContext() throws MalformedURLException {
        final WebAppContext handler = new WebAppContext();
        handler.setContextPath("/");
        handler.setBaseResourceAsPath(webAppRoot().toPath());
        final Path helloJar = new File(webAppRoot(),
                                       "WEB-INF" + File.separatorChar +
                                       "lib" + File.separatorChar +
                                       "hello.jar").toPath();
        handler.setClassLoader(new URLClassLoader(new URL[] {
                ResourceFactory.root().newResource(helloJar).getURI().toURL()
        }, JettyService.class.getClassLoader()));
        return handler;
    }

    @Override
    protected ServerExtension server() {
        return server;
    }

    @Test
    void configurator() throws Exception {
        assertThat(jettyBeans)
                .hasAtLeastOneElementOfType(ThreadPool.class)
                .hasAtLeastOneElementOfType(WebAppContext.class);
    }

    @Test
    void defaultHandlerFavicon() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/default/favicon.ico")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.parse("image/x-icon"));
        assertThat(res.content().length()).isGreaterThan(0);
    }

    @Test
    void resourceHandlerWithLargeResource() throws Exception {
        testLarge("/resources/large.txt", true);
    }

    @Test
    void headersOnly() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/headers-only")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.NO_CONTENT);
        assertThat(res.headers()).containsAll(HttpHeaders.of("x-headers", "foo"));
        assertThat(res.trailers()).isEmpty();
    }

    @Test
    void headersAndTrailers() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/headers-trailers")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers()).containsAll(HttpHeaders.of("x-headers", "bar"));
        assertThat(res.content().length()).isZero();
        assertThat(res.trailers()).containsAll(HttpHeaders.of("x-trailers", "baz"));
    }

    @Test
    void headersDataAndTrailers() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/headers-data-trailers")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers()).containsAll(HttpHeaders.of("x-headers", "bar"));
        assertThat(res.contentAscii()).isEqualTo("qux");
        assertThat(res.trailers()).containsAll(HttpHeaders.of("x-trailers", "baz"));
    }

    /**
     * An {@link IOException} or {@link EofException} should be raised if a handler closed
     * its {@code ServletOutputStream} and then tries to write something to it.
     */
    @Test
    void writingAfterCompletion() {
        capturedException.set(null);
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/write-after-completion")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("before close");
        // An `IOException` should be raised when writing after closing the `ServletOutputStream`.
        await().untilAsserted(() -> assertThat(capturedException).isNotNull());
        final Throwable cause = capturedException.get();
        assertThat(cause).isInstanceOf(IOException.class)
                         .hasMessage("written 23 > 12 content-length");
    }

    @Test
    @Override
    public void echoPostWithEmptyBody() throws Exception {
        super.echoPostWithEmptyBody();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void sendingResponseOnDisconnectedConnection(SessionProtocol protocol) {
        capturedException.set(null);
        // Send a request that doesn't time out until the client gives up.
        // The client will give up quickly and disconnect.
        final String uri = protocol.uriText() + "://127.0.0.1:" + server.httpPort() +
                           "/timeout/" + Integer.MAX_VALUE;
        assertThatThrownBy(() -> {
            WebClient.of()
                     .prepare()
                     .get(uri)
                     .responseTimeoutMillis(1)
                     .execute()
                     .aggregate()
                     .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ResponseTimeoutException.class);

        // No exception should be raised when a Jetty handler writes something after timeout.
        await().untilAsserted(() -> assertThat(capturedException).hasValue(NO_EXCEPTION));
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void sendingResponseToTimedOutRequest(SessionProtocol protocol) {
        capturedException.set(null);
        // Send a request that times out after 1ms.
        final String uri = protocol.uriText() + "://127.0.0.1:" + server.httpPort() + "/timeout/1";
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(uri)
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
        // No exception should be raised when a Jetty handler writes something after timeout.
        await().untilAsserted(() -> assertThat(capturedException).hasValue(NO_EXCEPTION));
    }

    /**
     * Makes sure asynchronous streaming works for various sizes of responses.
     */
    @ParameterizedTest
    @CsvSource({
            "8192, 8192",     // 8KiB in a single write
            "8192, 128",      // 8KiB in many writes
            "131072, 131072", // 128KiB in a single write
            "131072, 8192",   // 128KiB in many writes
    })
    void asyncRequest(int totalSize, int chunkSize) throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/stream/" + totalSize + '/' + chunkSize)
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentAscii()).hasSize(totalSize)
                                      .matches("^(?:0123456789abcdef)*$");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void throwingHandler(SessionProtocol sessionProtocol) throws Exception {
        final AggregatedHttpResponse res = WebClient.builder(sessionProtocol, server.httpEndpoint())
                                                    .build().blocking().get("/throwing");
        assertThat(res.status().code()).isEqualTo(500);

        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final ServiceRequestContext sctx = server.requestContextCaptor().poll();
        await().atMost(10, TimeUnit.SECONDS).until(() -> sctx.log().isComplete());
        assertThat(sctx.log().ensureComplete().responseCause()).isSameAs(RUNTIME_EXCEPTION);
    }

    @Test
    @Override
    public void addressesAndPorts_127001() throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server().httpUri()).blocking()
                                                         .get("/jsp/addrs_and_ports.jsp");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).matches(
                "<html><body>" +
                "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                "<p>RemotePort: [1-9][0-9]+</p>" +
                "<p>LocalAddr: (?!null)[^<]+</p>" +
                // In Jetty 12, ServletRequest.getLocalName() returns the IP address if it is resolved.
                "<p>LocalName: 127\\.0\\.0\\.1</p>" +
                "<p>LocalPort: " + server().httpPort() + "</p>" +
                "<p>ServerName: 127\\.0\\.0\\.1</p>" +
                "<p>ServerPort: " + server().httpPort() + "</p>" +
                "</body></html>");
    }

    @Test
    @Override
    public void addressesAndPorts_localhost() throws Exception {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/jsp/addrs_and_ports.jsp", "Host",
                                                         "localhost:1111");
        final AggregatedHttpResponse response = WebClient.of(server().httpUri()).blocking().execute(headers);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).matches(
                "<html><body>" +
                "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                "<p>RemotePort: [1-9][0-9]+</p>" +
                "<p>LocalAddr: (?!null)[^<]+</p>" +
                // In Jetty 12, ServletRequest.getLocalName() returns the IP address if it is resolved.
                "<p>LocalName: 127\\.0\\.0\\.1</p>" +
                "<p>LocalPort: " + server().httpPort() + "</p>" +
                "<p>ServerName: localhost</p>" +
                "<p>ServerPort: 1111</p>" +
                "</body></html>");
    }
}
