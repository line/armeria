/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.logging;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.StringUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A built-in property exported by {@link RequestContextExporter}.
 *
 * @see RequestContextExporterBuilder#builtIn(BuiltInProperty)
 */
public enum BuiltInProperty {
    /**
     * {@code "remote.host"} - the host name part of the remote socket address. Unavailable if the connection
     * is not established yet.
     */
    REMOTE_HOST("remote.host", log -> {
        final InetSocketAddress addr = log.context().remoteAddress();
        return addr != null ? addr.getHostString() : null;
    }),
    /**
     * {@code "remote.ip"} - the IP address part of the remote socket address. Unavailable if the connection
     * is not established yet.
     */
    REMOTE_IP("remote.ip", log -> {
        final InetSocketAddress addr = log.context().remoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : null;
    }),
    /**
     * {@code "remote.port"} - the port number part of the remote socket address. Unavailable if the connection
     * is not established yet.
     */
    REMOTE_PORT("remote.port", log -> {
        final InetSocketAddress addr = log.context().remoteAddress();
        return addr != null ? StringUtil.toString(addr.getPort()) : null;
    }),
    /**
     * {@code "local.host"} - the host name part of the local socket address. Unavailable if the connection
     * is not established yet.
     */
    LOCAL_HOST("local.host", log -> {
        final InetSocketAddress addr = log.context().localAddress();
        return addr != null ? addr.getHostString() : null;
    }),
    /**
     * {@code "local.ip"} - the IP address part of the local socket address. Unavailable if the connection
     * is not established yet.
     */
    LOCAL_IP("local.ip", log -> {
        final InetSocketAddress addr = log.context().localAddress();
        return addr != null ? addr.getAddress().getHostAddress() : null;
    }),
    /**
     * {@code "local.port"} - the port number part of the local socket address. Unavailable if the connection
     * is not established yet.
     */
    LOCAL_PORT("local.port", log -> {
        final InetSocketAddress addr = log.context().localAddress();
        return addr != null ? StringUtil.toString(addr.getPort()) : null;
    }),
    /**
     * {@code "client.ip"} - the IP address who initiated a request. Unavailable if the connection is not
     * established yet.
     */
    CLIENT_IP("client.ip", log -> {
        final RequestContext ctx = log.context();
        final InetAddress caddr =
                ctx instanceof ServiceRequestContext ? ((ServiceRequestContext) ctx).clientAddress() : null;
        return caddr != null ? caddr.getHostAddress() : null;
    }),
    /**
     * {@code "scheme"} - the scheme of the request, represented by {@link Scheme#uriText()}, such as
     * {@code "tbinary+h2"}.
     */
    SCHEME("scheme", log -> {
        if (log.isAvailable(RequestLogProperty.SCHEME)) {
            return log.scheme().uriText();
        } else {
            return "unknown+" + log.context().sessionProtocol().uriText();
        }
    }),
    /**
     * {@code "elapsed_nanos"} - the amount of time in nanoseconds taken to handle the request. Unavailable if
     * the request was not handled completely yet.
     */
    ELAPSED_NANOS("elapsed_nanos", log -> {
        if (log.isAvailable(RequestLogProperty.RESPONSE_END_TIME)) {
            return String.valueOf(log.totalDurationNanos());
        }
        return null;
    }),
    /**
     * {@code "req.direction"} - the direction of the request, which is {@code "INBOUND"} for servers and
     * {@code "OUTBOUND"} for clients.
     */
    REQ_DIRECTION("req.direction", log -> {
        final RequestContext ctx = log.context();
        if (ctx instanceof ServiceRequestContext) {
            return "INBOUND";
        } else if (ctx instanceof ClientRequestContext) {
            return "OUTBOUND";
        } else {
            return "UNKNOWN";
        }
    }),
    /**
     * {@code "req.authority"} - the authority of the request, represented as {@code "<hostname>[:<port>]"}.
     * The port number is omitted when it is same with the default port number of the current {@link Scheme}.
     */
    REQ_AUTHORITY("req.authority", BuiltInProperty::getAuthority),
    /**
     * {@code "req.id"} - the ID of the request.
     */
    REQ_ID("req.id", log -> log.context().id().text()),
    /**
     * {@code "req.root_id"} - the ID of the root service request.
     * {@code null} if {@link RequestContext#root()} returns {@code null}.
     */
    REQ_ROOT_ID("req.root_id", log -> {
        final ServiceRequestContext rootCtx = log.context().root();
        return rootCtx != null ? rootCtx.id().text() : null;
    }),
    /**
     * {@code "req.path"} - the path of the request.
     */
    REQ_PATH("req.path", log -> log.context().path()),
    /**
     * {@code "req.query"} - the query of the request.
     */
    REQ_QUERY("req.query", log -> log.context().query()),
    /**
     * {@code "req.method"} - the method name of the request, such as {@code "GET"} and {@code "POST"}.
     */
    REQ_METHOD("req.method", log -> log.context().method().name()),
    /**
     * {@code "req.name"} - the human-readable name of the request, such as:
     * <ul>
     *   <li>gRPC - A capitalized method name defined in {@code io.grpc.MethodDescriptor}
     *       (e.g, {@code GetItems})</li>
     *   <li>Thrift and annotated service - a method name (e.g, {@code getItems})</li>
     *   <li>{@link HttpService} - an HTTP method name</li>
     * </ul>
     * This property is often used as a meter tag or distributed trace's span name.
     */
    REQ_NAME("req.name", log -> log.isAvailable(RequestLogProperty.NAME) ? log.name() : null),
    /**
     * {@code "req.service_name"} - the human-readable name of the service that served the request, such as:
     * <ul>
     *   <li>gRPC - a service name (e.g, {@code com.foo.GrpcService})</li>
     *   <li>Thrift - a service type (e.g, {@code com.foo.ThriftService$AsyncIface} or
     *       {@code com.foo.ThriftService$Iface})</li>
     *   <li>{@link HttpService} and annotated service - an innermost class name</li>
     * </ul>
     * This property is often used as a meter tag or distributed trace's span name.
     */
    REQ_SERVICE_NAME("req.service_name",
                     log -> log.isAvailable(RequestLogProperty.NAME) ? log.serviceName() : null),

    /**
     * {@code "req.content_length"} - the byte-length of the request content. Unavailable if the current
     * request is not fully received yet.
     */
    REQ_CONTENT_LENGTH("req.content_length", log -> {
        if (log.isAvailable(RequestLogProperty.REQUEST_LENGTH)) {
            return StringUtil.toString(log.requestLength());
        }
        return null;
    }),

    /**
     * {@code "req.content"} - the content of the request. The content may have one of the following:
     *
     * <table>
     * <caption>the content of the request</caption>
     * <tr><th>request type</th><th>description</th></tr>
     *
     * <tr><td>RPC</td>
     * <td>The RPC parameter list, represented by {@link Arrays#toString(Object...)} for the {@link RpcRequest}.
     * Unavailable if the current request is not an RPC request or is not decoded yet.</td></tr>
     *
     * <tr><td>HTTP</td>
     * <td>The preview content of the {@link Request}.
     * Unavailable if the preview is disabled or not fully produced yet.</td></tr>
     *
     * </table>
     */
    REQ_CONTENT("req.content", log -> {
        if (log.isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            final Object requestContent = log.requestContent();
            if (requestContent instanceof RpcRequest) {
                return String.valueOf(((RpcRequest) requestContent).params());
            }
        }
        if (log.isAvailable(RequestLogProperty.REQUEST_CONTENT_PREVIEW)) {
            return log.requestContentPreview();
        }
        return null;
    }),

    /**
     * {@code "res.status_code"} - the protocol-specific integer representation of the response status code.
     * Unavailable if the current response is not fully sent yet.
     */
    RES_STATUS_CODE("res.status_code", log -> {
        if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            return log.responseHeaders().status().codeAsText();
        }
        return null;
    }),

    /**
     * {@code "res.content_length"} - the byte-length of the response content. Unavailable if the current
     * response is not fully sent yet.
     */
    RES_CONTENT_LENGTH("res.content_length", log -> {
        if (log.isAvailable(RequestLogProperty.RESPONSE_LENGTH)) {
            return StringUtil.toString(log.responseLength());
        }
        return null;
    }),

    /**
     * {@code "res.content"} - the content of the response. The content may have one of the following:
     *
     * <table>
     * <caption>the content of the response</caption>
     * <tr><th>response type</th><th>description</th></tr>
     *
     * <tr><td>RPC</td>
     * <td>The RPC result value of the {@link RpcResponse}.
     * Unavailable if the current response is not fully sent yet.</td></tr>
     *
     * <tr><td>HTTP</td>
     * <td>The preview content of the {@link Response}.
     * Unavailable if the preview is disabled or not fully produced yet.</td></tr>
     *
     * </table>
     */
    RES_CONTENT("res.content", log -> {
        if (log.isAvailable(RequestLogProperty.RESPONSE_CONTENT)) {
            final Object responseContent = log.responseContent();
            if (responseContent instanceof RpcResponse) {
                final RpcResponse rpcRes = (RpcResponse) responseContent;
                if (!rpcRes.isCompletedExceptionally()) {
                    return String.valueOf(rpcRes.join());
                }
            }
        }
        if (log.isAvailable(RequestLogProperty.RESPONSE_CONTENT_PREVIEW)) {
            return log.responseContentPreview();
        }
        return null;
    }),

    /**
     * {@code "tls.session_id"} - the hexadecimal representation of the current
     * {@linkplain SSLSession#getId() TLS session ID}. Unavailable if TLS handshake is not finished or
     * the connection is not a TLS connection.
     */
    TLS_SESSION_ID("tls.session_id", log -> {
        final SSLSession s = log.context().sslSession();
        if (s != null) {
            final byte[] id = s.getId();
            if (id != null) {
                return lowerCasedBase16().encode(id);
            }
        }
        return null;
    }),

    /**
     * {@code "tls.cipher"} - the current {@linkplain SSLSession#getCipherSuite() TLS cipher suite}.
     * Unavailable if TLS handshake is not finished or the connection is not a TLS connection, such as
     * {@code "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"}.
     */
    TLS_CIPHER("tls.cipher", log -> {
        final SSLSession s = log.context().sslSession();
        return s != null ? s.getCipherSuite() : null;
    }),

    /**
     * {@code "tls.proto"} - the current {@linkplain SSLSession#getProtocol() TLS protocol}.
     * Unavailable if TLS handshake is not finished or the connection is not a TLS connection, such as
     * {@code "TLSv1.2"}.
     */
    TLS_PROTO("tls.proto", log -> {
        final SSLSession s = log.context().sslSession();
        return s != null ? s.getProtocol() : null;
    });

    private static final Map<String, BuiltInProperty> keyToEnum;

    static final String WILDCARD_STR = "*";
    static final String WILDCARD_REGEX = '\\' + WILDCARD_STR;
    private static final Pattern PORT_443 = Pattern.compile(":0*443$");
    private static final Pattern PORT_80 = Pattern.compile(":0*80$");
    private static final BaseEncoding lowerCasedBase16 = BaseEncoding.base16().lowerCase();

    static {
        final ImmutableMap.Builder<String, BuiltInProperty> builder = ImmutableMap.builder();
        for (BuiltInProperty k : BuiltInProperty.values()) {
            builder.put(k.key, k);
        }
        keyToEnum = builder.build();
    }

    static List<BuiltInProperty> findByKeyPattern(String keyPattern) {
        final Pattern pattern = Pattern.compile(
                ("\\Q" + keyPattern + "\\E").replaceAll(WILDCARD_REGEX, "\\\\E.*\\\\Q"));
        return keyToEnum.entrySet().stream()
                        .filter(e -> pattern.matcher(e.getKey()).matches())
                        .map(Entry::getValue)
                        .collect(toImmutableList());
    }

    @Nullable
    static BuiltInProperty findByKey(String key) {
        return keyToEnum.entrySet().stream()
                        .filter(e -> e.getKey().equals(key))
                        .map(Entry::getValue)
                        .findFirst().orElse(null);
    }

    private static BaseEncoding lowerCasedBase16() {
        return lowerCasedBase16;
    }

    private static String getAuthority(RequestLog log) {
        final RequestContext ctx = log.context();
        if (log.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
            final String authority = getAuthority0(ctx, log.requestHeaders());
            if (authority != null) {
                return authority;
            }
        }

        final HttpRequest origReq = ctx.request();
        if (origReq != null) {
            final String authority = getAuthority0(ctx, origReq.headers());
            if (authority != null) {
                return authority;
            }
        }

        final String authority;
        if (ctx instanceof ServiceRequestContext) {
            final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
            final int port = ((InetSocketAddress) sCtx.remoteAddress()).getPort();
            final String hostname = sCtx.config().virtualHost().defaultHostname();
            if (port == ctx.sessionProtocol().defaultPort()) {
                authority = hostname;
            } else {
                authority = hostname + ':' + port;
            }
        } else {
            final ClientRequestContext cCtx = (ClientRequestContext) ctx;
            final Endpoint endpoint = cCtx.endpoint();
            if (endpoint == null) {
                authority = "UNKNOWN";
            } else {
                final int defaultPort = cCtx.sessionProtocol().defaultPort();
                final int port = endpoint.port(defaultPort);
                if (port == defaultPort) {
                    authority = endpoint.host();
                } else {
                    authority = endpoint.host() + ':' + port;
                }
            }
        }
        return authority;
    }

    @Nullable
    private static String getAuthority0(RequestContext ctx, HttpHeaders headers) {
        String authority = headers.get(HttpHeaderNames.AUTHORITY);
        if (authority != null) {
            final Pattern portPattern = ctx.sessionProtocol().isTls() ? PORT_443 : PORT_80;
            final Matcher m = portPattern.matcher(authority);
            if (m.find()) {
                authority = authority.substring(0, m.start());
            }
            return authority;
        }

        return null;
    }

    final String key;
    final Function<? super RequestLog, String> converter;

    BuiltInProperty(String key, Function<? super RequestLog, String> converter) {
        this.key = key;
        this.converter = converter;
    }
}
