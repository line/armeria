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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toHttp1Headers;
import static com.linecorp.armeria.internal.common.util.MappedPathUtil.mappedPath;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.net.ssl.SSLSession;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.server.servlet.ServletTlsAttributes;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="https://www.eclipse.org/jetty/">Jetty</a>.
 *
 * @see JettyServiceBuilder
 */
public final class JettyService implements HttpService {

    static final Logger logger = LoggerFactory.getLogger(JettyService.class);

    /**
     * Dynamically resolve {@link Request#setAsyncSupported(boolean, Object)} because its signature
     * has been changed since 9.4.
     */
    private static final MethodHandle jReqSetAsyncSupported;

    /**
     * Dynamically resolve {@link MetaData.Response#getTrailerSupplier()} because it doesn't exist in 9.3.
     */
    @Nullable
    private static final MethodHandle jResGetTrailerSupplier;

    static {
        MethodHandle setAsyncSupported = null;
        try {
            // Jetty 9.4+
            setAsyncSupported = MethodHandles.lookup().unreflect(
                    Request.class.getMethod("setAsyncSupported", boolean.class, Object.class));
        } catch (Throwable t) {
            try {
                // Jetty 9.3 or below
                //noinspection JavaReflectionMemberAccess
                setAsyncSupported = MethodHandles.lookup().unreflect(
                        Request.class.getMethod("setAsyncSupported", boolean.class, String.class));
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                Exceptions.throwUnsafely(t2);
            }
        }

        assert setAsyncSupported != null;
        jReqSetAsyncSupported = setAsyncSupported;

        MethodHandle getTrailerSupplier = null;
        try {
            // Jetty 9.4+
            getTrailerSupplier = MethodHandles.lookup().unreflect(
                    MetaData.Response.class.getMethod("getTrailerSupplier"));
        } catch (Throwable t) {
            // Jetty 9.3 or below
            getTrailerSupplier = null;
        }
        jResGetTrailerSupplier = getTrailerSupplier;
    }

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     */
    public static JettyService of(Server jettyServer) {
        return of(jettyServer, null);
    }

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     * @param hostname the default hostname, or {@code null} to use Armeria's default virtual host name.
     */
    public static JettyService of(Server jettyServer, @Nullable String hostname) {
        return of(jettyServer, hostname, false);
    }

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     * @param hostname the default hostname, or {@code null} to use Armeria's default virtual host name.
     * @param tlsReverseDnsLookup whether perform reverse DNS lookup for the remote IP address on a TLS
     *                            connection. See {@link JettyServiceBuilder#tlsReverseDnsLookup(boolean)}
     *                            for more information.
     */
    public static JettyService of(Server jettyServer, @Nullable String hostname, boolean tlsReverseDnsLookup) {
        requireNonNull(jettyServer, "jettyServer");
        return new JettyService(hostname, tlsReverseDnsLookup, blockingTaskExecutor -> jettyServer);
    }

    /**
     * Returns a new {@link JettyServiceBuilder}.
     */
    public static JettyServiceBuilder builder() {
        return new JettyServiceBuilder();
    }

    private final Function<BlockingTaskExecutor, Server> serverFactory;
    private final Consumer<Server> postStopTask;
    private final Configurator configurator;

    @Nullable
    private final String hostname;
    private final boolean tlsReverseDnsLookup;
    @Nullable
    private Server server;
    @Nullable
    private ArmeriaConnector connector;

    private com.linecorp.armeria.server.@Nullable Server armeriaServer;
    private boolean startedServer;

    private JettyService(@Nullable String hostname, boolean tlsReverseDnsLookup,
                         Function<BlockingTaskExecutor, Server> serverSupplier) {
        this(hostname, tlsReverseDnsLookup, serverSupplier, unused -> { /* unused */ });
    }

    JettyService(@Nullable String hostname,
                 boolean tlsReverseDnsLookup,
                 Function<BlockingTaskExecutor, Server> serverFactory,
                 Consumer<Server> postStopTask) {

        this.hostname = hostname;
        this.tlsReverseDnsLookup = tlsReverseDnsLookup;
        this.serverFactory = serverFactory;
        this.postStopTask = postStopTask;
        configurator = new Configurator();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) {
        if (armeriaServer != null) {
            if (armeriaServer != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        armeriaServer = cfg.server();
        armeriaServer.addListener(configurator);
    }

    void start() throws Exception {
        boolean success = false;
        try {
            assert armeriaServer != null;
            server = serverFactory.apply(armeriaServer.config().blockingTaskExecutor());
            connector = new ArmeriaConnector(server, armeriaServer);
            server.addConnector(connector);

            if (!server.isRunning()) {
                logger.info("Starting an embedded Jetty: {}", server);
                server.start();
                startedServer = true;
            } else {
                startedServer = false;
            }
            success = true;
        } finally {
            if (!success) {
                server = null;
                connector = null;
            }
        }
    }

    void stop() {
        final Server server = this.server;
        this.server = null;
        connector = null;

        if (server == null || !startedServer) {
            return;
        }

        try {
            logger.info("Stopping an embedded Jetty: {}", server);
            server.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop an embedded Jetty: {}", server, e);
        }

        postStopTask.accept(server);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final String mappedPath = mappedPath(ctx);
        if (mappedPath == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Invalid matrix variable: " +
                                   ctx.routingContext().requestTarget().maybePathWithMatrixVariables());
        }
        final ArmeriaConnector connector = this.connector;
        assert connector != null;

        final HttpResponseWriter res = HttpResponse.streaming();

        req.aggregate().handle((aReq, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                logger.warn("{} Failed to aggregate a request:", ctx, cause);
                if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
                    res.close(cause);
                } else if (res.tryWrite(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR))) {
                    res.close();
                }
                return null;
            }

            try {
                final ArmeriaHttpTransport transport = new ArmeriaHttpTransport(ctx, res);
                final HttpChannel httpChannel = new HttpChannel(
                        connector,
                        connector.getHttpConfiguration(),
                        new ArmeriaEndPoint(ctx, hostname),
                        transport);

                // Armeria has its own timeout mechanism. Disable Jetty's timeout scheduler
                // and abort the Jetty transport when Armeria request is timed out.
                httpChannel.getState().setTimeout(0);
                ctx.whenRequestCancelling().handle((cancellationCause, unused) -> {
                    httpChannel.abort(cancellationCause);
                    return null;
                });

                final Request jReq = httpChannel.getRequest();
                fillRequest(ctx, aReq, jReq);
                final SSLSession sslSession = ctx.sslSession();
                final boolean needsReverseDnsLookup;
                if (sslSession != null) {
                    needsReverseDnsLookup = tlsReverseDnsLookup;
                    ServletTlsAttributes.fill(sslSession, jReq::setAttribute);
                } else {
                    needsReverseDnsLookup = false;
                }

                ctx.blockingTaskExecutor().execute(() -> {
                    // Perform a reverse DNS lookup if needed.
                    if (needsReverseDnsLookup) {
                        try {
                            ctx.remoteAddress().getHostName();
                        } catch (Throwable t) {
                            logger.warn("{} Failed to perform a reverse DNS lookup:", ctx, t);
                        }
                    }

                    // Let Jetty handle the request.
                    try {
                        httpChannel.handle();
                    } catch (Throwable t) {
                        logger.warn("{} Failed to handle a request:", ctx, t);
                    }
                });
            } catch (Throwable t) {
                res.abort(t);
            }
            return null;
        }).exceptionally(CompletionActions::log);

        return res;
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return ExchangeType.RESPONSE_STREAMING;
    }

    private static void fillRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest aReq, Request jReq) {

        jReq.setDispatcherType(DispatcherType.REQUEST);
        try {
            jReqSetAsyncSupported.invoke(jReq, true, "armeria");
        } catch (Throwable t) {
            // Should never reach here.
            Exceptions.throwUnsafely(t);
        }
        jReq.setSecure(ctx.sessionProtocol().isTls());
        jReq.setMetaData(toRequestMetadata(ctx, aReq));

        final HttpData content = aReq.content();
        if (!content.isEmpty()) {
            final ByteBuf buf = content.byteBuf();
            final ByteBuffer nioBuf;
            if (buf.nioBufferCount() == 1) {
                nioBuf = buf.nioBuffer();
            } else {
                nioBuf = ByteBuffer.wrap(content.array());
            }
            jReq.getHttpInput().addContent(new Content(nioBuf));
        }
        jReq.getHttpInput().eof();
    }

    private static MetaData.Request toRequestMetadata(ServiceRequestContext ctx, AggregatedHttpRequest aReq) {
        // Construct the HttpURI
        final StringBuilder uriBuf = new StringBuilder();
        final RequestHeaders aHeaders = aReq.headers();

        uriBuf.append(ctx.sessionProtocol().isTls() ? "https" : "http");
        uriBuf.append("://");
        uriBuf.append(aHeaders.authority());
        uriBuf.append(aHeaders.path());

        final HttpURI uri = new HttpURI(uriBuf.toString());
        uri.setPath(ctx.mappedPath());

        // Convert HttpHeaders to HttpFields
        final HttpFields jHeaders = new HttpFields(aHeaders.size());
        toHttp1Headers(aHeaders, jHeaders, (output, key, value) -> output.add(key.toString(), value));

        return new MetaData.Request(aHeaders.get(HttpHeaderNames.METHOD), uri,
                                    ctx.sessionProtocol().isMultiplex() ? HttpVersion.HTTP_2
                                                                        : HttpVersion.HTTP_1_1,
                                    jHeaders, aReq.content().length());
    }

    private static final class ArmeriaHttpTransport implements HttpTransport {

        private final ServiceRequestContext ctx;
        private final HttpResponseWriter res;

        MetaData.@Nullable Response info;

        ArmeriaHttpTransport(ServiceRequestContext ctx, HttpResponseWriter res) {
            this.ctx = ctx;
            this.res = res;
        }

        @Override
        public void send(MetaData.@Nullable Response info, boolean head,
                         @Nullable ByteBuffer content, boolean lastContent, Callback callback) {
            if (ctx.isTimedOut()) {
                // Silently discard the write request in case of timeout to match the behavior of Jetty.
                callback.succeeded();
                return;
            }

            try {
                if (info != null) {
                    this.info = info;
                    write(toResponseHeaders(info));
                }

                final int length = content != null ? content.remaining() : 0;
                if (!head && length != 0) {
                    final HttpData data;
                    if (content.hasArray()) {
                        final int from = content.arrayOffset() + content.position();
                        content.position(content.position() + length);
                        data = HttpData.wrap(Arrays.copyOfRange(content.array(), from, from + length));
                    } else {
                        final byte[] buf = new byte[length];
                        content.get(buf);
                        data = HttpData.wrap(buf);
                    }

                    if (lastContent) {
                        final HttpHeaders trailers = toResponseTrailers(info);
                        if (trailers != null) {
                            write(data);
                            write(trailers);
                        } else {
                            write(data.withEndOfStream());
                        }
                        res.close();
                    } else {
                        write(data);
                    }
                } else if (lastContent) {
                    final HttpHeaders trailers = toResponseTrailers(info);
                    if (trailers != null) {
                        write(trailers);
                    }
                    res.close();
                }

                callback.succeeded();
            } catch (Throwable cause) {
                callback.failed(cause);
            }
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        private void write(HttpObject o) {
            res.tryWrite(o);
        }

        private static ResponseHeaders toResponseHeaders(MetaData.Response info) {
            final ResponseHeadersBuilder headers = ResponseHeaders.builder();
            headers.status(info.getStatus());
            info.getFields().forEach(e -> headers.add(HttpHeaderNames.of(e.getName()), e.getValue()));
            return headers.build();
        }

        @Nullable
        private HttpHeaders toResponseTrailers(MetaData.@Nullable Response info) {
            if (jResGetTrailerSupplier == null) {
                return null;
            }

            if (info == null) {
                info = this.info;
                if (info == null) {
                    return null;
                }
            }

            final Supplier<HttpFields> trailerSupplier;
            try {
                //noinspection unchecked
                trailerSupplier = (Supplier<HttpFields>) jResGetTrailerSupplier.invoke(info);
            } catch (Throwable t) {
                return Exceptions.throwUnsafely(t);
            }

            if (trailerSupplier == null) {
                return null;
            }

            final HttpFields fields = trailerSupplier.get();
            if (fields == null || fields.size() == 0) {
                return null;
            }

            final HttpHeadersBuilder headers = HttpHeaders.builder();
            fields.forEach(e -> headers.add(HttpHeaderNames.of(e.getName()), e.getValue()));
            return headers.build();
        }

        @Override
        public boolean isPushSupported() {
            return false;
        }

        @Override
        public void push(MetaData.Request request) {}

        @Override
        public void onCompleted() {
            res.close();
        }

        @Override
        public void abort(Throwable failure) {
            res.close(failure);
        }

        @Override
        public boolean isOptimizedForDirectBuffers() {
            return false;
        }
    }

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(com.linecorp.armeria.server.Server server) throws Exception {
            start();
        }

        @Override
        public void serverStopped(com.linecorp.armeria.server.Server server) throws Exception {
            stop();
        }
    }
}
