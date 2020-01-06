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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.common.logging.BuiltInProperty.CLIENT_IP;
import static com.linecorp.armeria.common.logging.BuiltInProperty.ELAPSED_NANOS;
import static com.linecorp.armeria.common.logging.BuiltInProperty.LOCAL_HOST;
import static com.linecorp.armeria.common.logging.BuiltInProperty.LOCAL_IP;
import static com.linecorp.armeria.common.logging.BuiltInProperty.LOCAL_PORT;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REMOTE_HOST;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REMOTE_IP;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REMOTE_PORT;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_AUTHORITY;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_CONTENT_LENGTH;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_DIRECTION;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_METHOD;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_PATH;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_QUERY;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_RPC_METHOD;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_RPC_PARAMS;
import static com.linecorp.armeria.common.logging.BuiltInProperty.RES_CONTENT_LENGTH;
import static com.linecorp.armeria.common.logging.BuiltInProperty.RES_RPC_RESULT;
import static com.linecorp.armeria.common.logging.BuiltInProperty.RES_STATUS_CODE;
import static com.linecorp.armeria.common.logging.BuiltInProperty.SCHEME;
import static com.linecorp.armeria.common.logging.BuiltInProperty.TLS_CIPHER;
import static com.linecorp.armeria.common.logging.BuiltInProperty.TLS_PROTO;
import static com.linecorp.armeria.common.logging.BuiltInProperty.TLS_SESSION_ID;
import static com.linecorp.armeria.common.logging.RequestContextExporterBuilder.PREFIX_ATTRS;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Exports the specified properties from current {@link RequestContext} to {@link Map}.
 */
public final class RequestContextExporter {

    private static final Pattern PORT_443 = Pattern.compile(":0*443$");
    private static final Pattern PORT_80 = Pattern.compile(":0*80$");

    private static final BaseEncoding lowerCasedBase16 = BaseEncoding.base16().lowerCase();

    @SuppressWarnings("rawtypes")
    private static final ExportEntry[] EMPTY_EXPORT_ENTRIES = new ExportEntry[0];

    private static final AttributeKey<State> STATE =
            AttributeKey.valueOf(RequestContextExporter.class, "STATE");

    /**
     * Returns a newly created {@link RequestContextExporterBuilder}.
     */
    public static RequestContextExporterBuilder builder() {
        return new RequestContextExporterBuilder();
    }

    private final ImmutableSet<BuiltInProperty> builtInPropertySet;
    private final BuiltInProperties builtInProperties;
    @Nullable
    private final ExportEntry<AttributeKey<?>>[] attrs;
    @Nullable
    private final ExportEntry<AsciiString>[] httpReqHeaders;
    @Nullable
    private final ExportEntry<AsciiString>[] httpResHeaders;

    @SuppressWarnings("unchecked")
    RequestContextExporter(Set<BuiltInProperty> builtInPropertySet,
                           Set<ExportEntry<AttributeKey<?>>> attrs,
                           Set<ExportEntry<AsciiString>> httpReqHeaders,
                           Set<ExportEntry<AsciiString>> httpResHeaders) {

        this.builtInPropertySet = ImmutableSet.copyOf(builtInPropertySet);
        builtInProperties = new BuiltInProperties();
        builtInPropertySet.forEach(builtInProperties::add);

        if (!attrs.isEmpty()) {
            this.attrs = attrs.toArray(EMPTY_EXPORT_ENTRIES);
        } else {
            this.attrs = null;
        }

        if (!httpReqHeaders.isEmpty()) {
            this.httpReqHeaders = httpReqHeaders.toArray(EMPTY_EXPORT_ENTRIES);
        } else {
            this.httpReqHeaders = null;
        }

        if (!httpResHeaders.isEmpty()) {
            this.httpResHeaders = httpResHeaders.toArray(EMPTY_EXPORT_ENTRIES);
        } else {
            this.httpResHeaders = null;
        }
    }

    /**
     * Returns {@code true} if the specified {@link AttributeKey} is in the export list.
     */
    public boolean containsAttribute(AttributeKey<?> key) {
        requireNonNull(key, "key");
        return Arrays.stream(attrs).anyMatch(e -> e.key.equals(key));
    }

    /**
     * Returns {@code true} if the specified HTTP request header name is in the export list.
     */
    public boolean containsHttpRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        return Arrays.stream(httpReqHeaders).anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    /**
     * Returns {@code true} if the specified HTTP response header name is in the export list.
     */
    public boolean containsHttpResponseHeader(CharSequence name) {
        requireNonNull(name, "name");
        return Arrays.stream(httpResHeaders).anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    /**
     * Returns {@code true} if the specified {@link BuiltInProperty} is in the export list.
     */
    public boolean containsBuiltIn(BuiltInProperty property) {
        return builtInProperties.contains(requireNonNull(property, "property"));
    }

    /**
     * Returns all {@link BuiltInProperty}s in the export list.
     */
    public Set<BuiltInProperty> builtIns() {
        return builtInPropertySet;
    }

    /**
     * Returns all {@link AttributeKey}s in the export list.
     *
     * @return the {@link Map} whose key is an alias and value is an {@link AttributeKey}
     */
    public Map<String, AttributeKey<?>> attributes() {
        return Arrays.stream(attrs).collect(
                toImmutableMap(e -> e.exportKey.substring(PREFIX_ATTRS.length()), e -> e.key));
    }

    /**
     * Returns all HTTP request header names in the export list.
     */
    public Set<AsciiString> httpRequestHeaders() {
        return Arrays.stream(httpReqHeaders).map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Returns all HTTP response header names in the export list.
     */
    public Set<AsciiString> httpResponseHeaders() {
        return Arrays.stream(httpResHeaders).map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Returns a {@link Map} whose key is an export key set through {@code add*()} in
     * {@link RequestContextExporterBuilder} and value is extracted from {@link RequestContext}.
     * Note that this method returns an empty {@link Map} if current {@link RequestContext} is {@code null}.
     */
    public Map<String, String> export() {
        final RequestContext ctx = RequestContext.currentOrNull();
        return ctx != null ? export(ctx) : ImmutableMap.of();
    }

    /**
     * Returns a {@link Map} whose key is an export key set through {@code add*()} in
     * {@link RequestContextExporterBuilder} and value is extracted from the specified {@link RequestContext}.
     */
    public Map<String, String> export(RequestContext ctx) {
        requireNonNull(ctx, "ctx");

        final State state = state(ctx);
        final RequestLog log = ctx.log();
        final Set<RequestLogAvailability> availabilities = log.availabilities();

        // Note: This equality check is extremely fast.
        //       See RequestLogAvailabilitySet for more information.
        if (!availabilities.equals(state.availabilities)) {
            state.availabilities = availabilities;
            export(state, ctx, log);
        }
        // Create a copy of 'state' to avoid the race between:
        // - the delegate appenders who iterate over the MDC map and
        // - this class who update 'state'.
        return state.clone();
    }

    private void export(Map<String, String> out, RequestContext ctx, RequestLog log) {
        // Built-ins
        if (builtInProperties.containsAddresses()) {
            exportAddresses(out, ctx);
        }
        if (builtInProperties.contains(SCHEME)) {
            exportScheme(out, ctx, log);
        }
        if (builtInProperties.contains(REQ_DIRECTION)) {
            exportDirection(out, ctx);
        }
        if (builtInProperties.contains(REQ_AUTHORITY)) {
            exportAuthority(out, ctx, log);
        }
        if (builtInProperties.contains(REQ_PATH)) {
            exportPath(out, ctx);
        }
        if (builtInProperties.contains(REQ_QUERY)) {
            exportQuery(out, ctx);
        }
        if (builtInProperties.contains(REQ_METHOD)) {
            exportMethod(out, ctx);
        }
        if (builtInProperties.contains(REQ_CONTENT_LENGTH)) {
            exportRequestContentLength(out, log);
        }
        if (builtInProperties.contains(RES_STATUS_CODE)) {
            exportStatusCode(out, log);
        }
        if (builtInProperties.contains(RES_CONTENT_LENGTH)) {
            exportResponseContentLength(out, log);
        }
        if (builtInProperties.contains(ELAPSED_NANOS)) {
            exportElapsedNanos(out, log);
        }

        // SSL
        if (builtInProperties.containsSsl()) {
            exportTlsProperties(out, ctx);
        }

        // RPC
        if (builtInProperties.containsRpc()) {
            exportRpcRequest(out, log);
            exportRpcResponse(out, log);
        }

        // Attributes
        exportAttributes(out, ctx);

        // HTTP headers
        exportHttpRequestHeaders(out, log);
        exportHttpResponseHeaders(out, log);
    }

    private void exportAddresses(Map<String, String> out, RequestContext ctx) {
        final InetSocketAddress raddr = ctx.remoteAddress();
        final InetSocketAddress laddr = ctx.localAddress();
        final InetAddress caddr =
                ctx instanceof ServiceRequestContext ? ((ServiceRequestContext) ctx).clientAddress() : null;

        if (raddr != null) {
            if (builtInProperties.contains(REMOTE_HOST)) {
                out.put(REMOTE_HOST.key, raddr.getHostString());
            }
            if (builtInProperties.contains(REMOTE_IP)) {
                out.put(REMOTE_IP.key, raddr.getAddress().getHostAddress());
            }
            if (builtInProperties.contains(REMOTE_PORT)) {
                out.put(REMOTE_PORT.key, String.valueOf(raddr.getPort()));
            }
        }

        if (laddr != null) {
            if (builtInProperties.contains(LOCAL_HOST)) {
                out.put(LOCAL_HOST.key, laddr.getHostString());
            }
            if (builtInProperties.contains(LOCAL_IP)) {
                out.put(LOCAL_IP.key, laddr.getAddress().getHostAddress());
            }
            if (builtInProperties.contains(LOCAL_PORT)) {
                out.put(LOCAL_PORT.key, String.valueOf(laddr.getPort()));
            }
        }

        if (caddr != null) {
            if (builtInProperties.contains(CLIENT_IP)) {
                out.put(CLIENT_IP.key, caddr.getHostAddress());
            }
        }
    }

    private static void exportScheme(Map<String, String> out, RequestContext ctx, RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.SCHEME)) {
            out.put(SCHEME.key, log.scheme().uriText());
        } else {
            out.put(SCHEME.key, "unknown+" + ctx.sessionProtocol().uriText());
        }
    }

    private static void exportDirection(Map<String, String> out, RequestContext ctx) {
        final String d;
        if (ctx instanceof ServiceRequestContext) {
            d = "INBOUND";
        } else if (ctx instanceof ClientRequestContext) {
            d = "OUTBOUND";
        } else {
            d = "UNKNOWN";
        }
        out.put(REQ_DIRECTION.key, d);
    }

    private static void exportAuthority(Map<String, String> out, RequestContext ctx, RequestLog log) {
        final Set<RequestLogAvailability> availabilities = log.availabilities();
        if (availabilities.contains(RequestLogAvailability.REQUEST_HEADERS)) {
            final String authority = getAuthority(ctx, log.requestHeaders());
            if (authority != null) {
                out.put(REQ_AUTHORITY.key, authority);
                return;
            }
        }

        final HttpRequest origReq = ctx.request();
        if (origReq != null) {
            final String authority = getAuthority(ctx, origReq.headers());
            if (authority != null) {
                out.put(REQ_AUTHORITY.key, authority);
                return;
            }
        }

        if (log.isAvailable(RequestLogAvailability.REQUEST_START)) {
            final String authority = log.authority();
            if (!"?".equals(authority)) {
                out.put(REQ_AUTHORITY.key, authority);
                return;
            }
        }

        final String authority;
        if (ctx instanceof ServiceRequestContext) {
            final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
            final int port = ((InetSocketAddress) sCtx.remoteAddress()).getPort();
            final String hostname = sCtx.virtualHost().defaultHostname();
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

        out.put(REQ_AUTHORITY.key, authority);
    }

    @Nullable
    private static String getAuthority(RequestContext ctx, HttpHeaders headers) {
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

    private static void exportPath(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_PATH.key, ctx.path());
    }

    private static void exportQuery(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_QUERY.key, ctx.query());
    }

    private static void exportMethod(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_METHOD.key, ctx.method().name());
    }

    private static void exportRequestContentLength(Map<String, String> out, RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.REQUEST_END)) {
            out.put(REQ_CONTENT_LENGTH.key, String.valueOf(log.requestLength()));
        }
    }

    private static void exportStatusCode(Map<String, String> out, RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
            out.put(RES_STATUS_CODE.key, log.status().codeAsText());
        }
    }

    private static void exportResponseContentLength(Map<String, String> out, RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.RESPONSE_END)) {
            out.put(RES_CONTENT_LENGTH.key, String.valueOf(log.responseLength()));
        }
    }

    private static void exportElapsedNanos(Map<String, String> out, RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.COMPLETE)) {
            out.put(ELAPSED_NANOS.key, String.valueOf(log.totalDurationNanos()));
        }
    }

    private void exportTlsProperties(Map<String, String> out, RequestContext ctx) {
        final SSLSession s = ctx.sslSession();
        if (s != null) {
            if (builtInProperties.contains(TLS_SESSION_ID)) {
                final byte[] id = s.getId();
                if (id != null) {
                    out.put(TLS_SESSION_ID.key, lowerCasedBase16.encode(id));
                }
            }
            if (builtInProperties.contains(TLS_CIPHER)) {
                final String cs = s.getCipherSuite();
                if (cs != null) {
                    out.put(TLS_CIPHER.key, cs);
                }
            }
            if (builtInProperties.contains(TLS_PROTO)) {
                final String p = s.getProtocol();
                if (p != null) {
                    out.put(TLS_PROTO.key, p);
                }
            }
        }
    }

    private void exportRpcRequest(Map<String, String> out, RequestLog log) {
        if (!log.isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
            return;
        }

        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            final RpcRequest rpcReq = (RpcRequest) requestContent;
            if (builtInProperties.contains(REQ_RPC_METHOD)) {
                out.put(REQ_RPC_METHOD.key, rpcReq.method());
            }
            if (builtInProperties.contains(REQ_RPC_PARAMS)) {
                out.put(REQ_RPC_PARAMS.key, String.valueOf(rpcReq.params()));
            }
        }
    }

    private void exportRpcResponse(Map<String, String> out, RequestLog log) {
        if (!log.isAvailable(RequestLogAvailability.RESPONSE_CONTENT)) {
            return;
        }

        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            final RpcResponse rpcRes = (RpcResponse) responseContent;
            if (builtInProperties.contains(RES_RPC_RESULT) &&
                !rpcRes.isCompletedExceptionally()) {
                try {
                    out.put(RES_RPC_RESULT.key, String.valueOf(rpcRes.get()));
                } catch (Exception e) {
                    // Should never reach here because RpcResponse must be completed.
                    throw new Error(e);
                }
            }
        }
    }

    private void exportAttributes(Map<String, String> out, RequestContext ctx) {
        if (attrs == null) {
            return;
        }

        for (ExportEntry<AttributeKey<?>> e : attrs) {
            final AttributeKey<?> attrKey = e.key;
            final String exportKey = e.exportKey;
            final Object value = ctx.attr(attrKey);
            if (value != null) {
                out.put(exportKey, e.stringify(value));
            }
        }
    }

    private void exportHttpRequestHeaders(Map<String, String> out, RequestLog log) {
        if (httpReqHeaders == null || !log.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return;
        }

        exportHttpHeaders(out, log.requestHeaders(), httpReqHeaders);
    }

    private void exportHttpResponseHeaders(Map<String, String> out, RequestLog log) {
        if (httpResHeaders == null || !log.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
            return;
        }

        exportHttpHeaders(out, log.responseHeaders(), httpResHeaders);
    }

    private static void exportHttpHeaders(Map<String, String> out, HttpHeaders headers,
                                          ExportEntry<AsciiString>[] requiredHeaderNames) {
        for (ExportEntry<AsciiString> e : requiredHeaderNames) {
            final String value = headers.get(e.key);
            final String exportKey = e.exportKey;
            if (value != null) {
                out.put(exportKey, e.stringify(value));
            }
        }
    }

    static final class ExportEntry<T> {
        final T key;
        final String exportKey;
        @Nullable
        final Function<Object, String> stringifier;

        @SuppressWarnings("unchecked")
        ExportEntry(T key, String exportKey, @Nullable Function<?, ?> stringifier) {
            assert key != null;
            assert exportKey != null;
            this.key = key;
            this.exportKey = exportKey;
            this.stringifier = (Function<Object, String>) stringifier;
        }

        @Nullable
        String stringify(@Nullable Object value) {
            if (stringifier == null) {
                return value != null ? value.toString() : null;
            } else {
                return stringifier.apply(value);
            }
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof ExportEntry)) {
                return false;
            }

            return key.equals(((ExportEntry<?>) o).key);
        }

        @Override
        public String toString() {
            return exportKey + ':' + key;
        }
    }

    private static State state(RequestContext ctx) {
        final State state = ctx.attr(STATE);
        if (state == null) {
            ctx.setAttr(STATE, new State());
            final State newState = new State();
            final State oldState = ctx.setAttrIfAbsent(STATE, newState);
            if (oldState != null) {
                return oldState;
            } else {
                return newState;
            }
        }
        return state;
    }

    private static final class State extends Object2ObjectOpenHashMap<String, String> {
        private static final long serialVersionUID = -7084248226635055988L;

        @Nullable
        Set<RequestLogAvailability> availabilities;
    }
}
