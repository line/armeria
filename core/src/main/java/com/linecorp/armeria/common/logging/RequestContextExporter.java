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
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_ID;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_METHOD;
import static com.linecorp.armeria.common.logging.BuiltInProperty.REQ_NAME;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.annotations.VisibleForTesting;
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

    @VisibleForTesting
    static final AttributeKey<State> STATE = AttributeKey.valueOf(RequestContextExporter.class, "STATE");

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
    private final int numAttrs;
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
            numAttrs = this.attrs.length;
        } else {
            this.attrs = null;
            numAttrs = 0;
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
        if (attrs == null) {
            return false;
        }
        return Arrays.stream(attrs).anyMatch(e -> e.key.equals(key));
    }

    /**
     * Returns {@code true} if the specified HTTP request header name is in the export list.
     */
    public boolean containsHttpRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        if (httpReqHeaders == null) {
            return false;
        }
        return Arrays.stream(httpReqHeaders).anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    /**
     * Returns {@code true} if the specified HTTP response header name is in the export list.
     */
    public boolean containsHttpResponseHeader(CharSequence name) {
        requireNonNull(name, "name");
        if (httpResHeaders == null) {
            return false;
        }
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
        if (attrs == null) {
            return ImmutableMap.of();
        }
        return Arrays.stream(attrs).collect(
                toImmutableMap(e -> e.exportKey.substring(PREFIX_ATTRS.length()), e -> e.key));
    }

    /**
     * Returns all HTTP request header names in the export list.
     */
    public Set<AsciiString> httpRequestHeaders() {
        if (httpReqHeaders == null) {
            return ImmutableSet.of();
        }
        return Arrays.stream(httpReqHeaders).map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Returns all HTTP response header names in the export list.
     */
    public Set<AsciiString> httpResponseHeaders() {
        if (httpResHeaders == null) {
            return ImmutableSet.of();
        }
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
        final RequestLogAccess log = ctx.log();
        boolean needsUpdate = false;

        // Needs to update if availabilityStamp has changed.
        // Also updates `State.availabilityStamp` while checking.
        final int availabilityStamp = log.availabilityStamp();
        if (state.availabilityStamp != availabilityStamp) {
            state.availabilityStamp = availabilityStamp;
            needsUpdate = true;
        }

        // Needs to update if any attributes have changed.
        // Also updates `State.attrValues` while scanning.
        if (attrs != null) {
            assert state.attrValues != null;
            for (int i = 0; i < attrs.length; i++) {
                final Object newValue = ctx.attr(attrs[i].key);
                if (!needsUpdate) {
                    final Object oldValue = state.attrValues[i];
                    needsUpdate = !Objects.equals(oldValue, newValue);
                }
                state.attrValues[i] = newValue;
            }
        }

        if (needsUpdate) {
            export(state, ctx, log.partial());
        }

        // Create a copy of 'state' to avoid the race between:
        // - the delegate appenders who iterate over the MDC map and
        // - this class who updates 'state'.
        return state.clone();
    }

    private void export(State state, RequestContext ctx, RequestLog log) {
        // Built-ins
        if (builtInProperties.containsAddresses()) {
            exportAddresses(state, ctx);
        }
        if (builtInProperties.contains(SCHEME)) {
            exportScheme(state, ctx, log);
        }
        if (builtInProperties.contains(REQ_DIRECTION)) {
            exportDirection(state, ctx);
        }
        if (builtInProperties.contains(REQ_AUTHORITY)) {
            exportAuthority(state, ctx, log);
        }
        if (builtInProperties.contains(REQ_ID)) {
            exportId(state, ctx);
        }
        if (builtInProperties.contains(REQ_PATH)) {
            exportPath(state, ctx);
        }
        if (builtInProperties.contains(REQ_QUERY)) {
            exportQuery(state, ctx);
        }
        if (builtInProperties.contains(REQ_METHOD)) {
            exportMethod(state, ctx);
        }
        if (builtInProperties.contains(REQ_NAME)) {
            exportName(state, log);
        }
        if (builtInProperties.contains(REQ_CONTENT_LENGTH)) {
            exportRequestContentLength(state, log);
        }
        if (builtInProperties.contains(RES_STATUS_CODE)) {
            exportStatusCode(state, log);
        }
        if (builtInProperties.contains(RES_CONTENT_LENGTH)) {
            exportResponseContentLength(state, log);
        }
        if (builtInProperties.contains(ELAPSED_NANOS)) {
            exportElapsedNanos(state, log);
        }

        // SSL
        if (builtInProperties.containsSsl()) {
            exportTlsProperties(state, ctx);
        }

        // RPC
        if (builtInProperties.containsRpc()) {
            exportRpcRequest(state, log);
            exportRpcResponse(state, log);
        }

        // Attributes
        exportAttributes(state);

        // HTTP headers
        exportHttpRequestHeaders(state, log);
        exportHttpResponseHeaders(state, log);
    }

    private void exportAddresses(State state, RequestContext ctx) {
        final InetSocketAddress raddr = ctx.remoteAddress();
        final InetSocketAddress laddr = ctx.localAddress();
        final InetAddress caddr =
                ctx instanceof ServiceRequestContext ? ((ServiceRequestContext) ctx).clientAddress() : null;

        if (raddr != null) {
            if (builtInProperties.contains(REMOTE_HOST)) {
                putBuiltInProperty(state, REMOTE_HOST, raddr.getHostString());
            }
            if (builtInProperties.contains(REMOTE_IP)) {
                putBuiltInProperty(state, REMOTE_IP, raddr.getAddress().getHostAddress());
            }
            if (builtInProperties.contains(REMOTE_PORT)) {
                putBuiltInProperty(state, REMOTE_PORT, String.valueOf(raddr.getPort()));
            }
        }

        if (laddr != null) {
            if (builtInProperties.contains(LOCAL_HOST)) {
                putBuiltInProperty(state, LOCAL_HOST, laddr.getHostString());
            }
            if (builtInProperties.contains(LOCAL_IP)) {
                putBuiltInProperty(state, LOCAL_IP, laddr.getAddress().getHostAddress());
            }
            if (builtInProperties.contains(LOCAL_PORT)) {
                putBuiltInProperty(state, LOCAL_PORT, String.valueOf(laddr.getPort()));
            }
        }

        if (caddr != null) {
            if (builtInProperties.contains(CLIENT_IP)) {
                putBuiltInProperty(state, CLIENT_IP, caddr.getHostAddress());
            }
        }
    }

    private static void exportScheme(State state, RequestContext ctx, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.SCHEME)) {
            putBuiltInProperty(state, SCHEME, log.scheme().uriText());
        } else {
            putBuiltInProperty(state, SCHEME, "unknown+" + ctx.sessionProtocol().uriText());
        }
    }

    private static void exportDirection(State state, RequestContext ctx) {
        final String d;
        if (ctx instanceof ServiceRequestContext) {
            d = "INBOUND";
        } else if (ctx instanceof ClientRequestContext) {
            d = "OUTBOUND";
        } else {
            d = "UNKNOWN";
        }
        putBuiltInProperty(state, REQ_DIRECTION, d);
    }

    private static void exportAuthority(State state, RequestContext ctx, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
            final String authority = getAuthority(ctx, log.requestHeaders());
            if (authority != null) {
                putBuiltInProperty(state, REQ_AUTHORITY, authority);
                return;
            }
        }

        final HttpRequest origReq = ctx.request();
        if (origReq != null) {
            final String authority = getAuthority(ctx, origReq.headers());
            if (authority != null) {
                putBuiltInProperty(state, REQ_AUTHORITY, authority);
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

        putBuiltInProperty(state, REQ_AUTHORITY, authority);
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

    private static void exportId(State state, RequestContext ctx) {
        putBuiltInProperty(state, REQ_ID, ctx.id().text());
    }

    private static void exportPath(State state, RequestContext ctx) {
        putBuiltInProperty(state, REQ_PATH, ctx.path());
    }

    private static void exportQuery(State state, RequestContext ctx) {
        putBuiltInProperty(state, REQ_QUERY, ctx.query());
    }

    private static void exportMethod(State state, RequestContext ctx) {
        putBuiltInProperty(state, REQ_METHOD, ctx.method().name());
    }

    private static void exportName(State state, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.NAME)) {
            final String name = log.name();
            if (name != null) {
                putBuiltInProperty(state, REQ_NAME, name);
            }
        }
    }

    private static void exportRequestContentLength(State state, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.REQUEST_LENGTH)) {
            putBuiltInProperty(state, REQ_CONTENT_LENGTH, String.valueOf(log.requestLength()));
        }
    }

    private static void exportStatusCode(State state, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            putBuiltInProperty(state, RES_STATUS_CODE, log.responseHeaders().status().codeAsText());
        }
    }

    private static void exportResponseContentLength(State state, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.RESPONSE_LENGTH)) {
            putBuiltInProperty(state, RES_CONTENT_LENGTH, String.valueOf(log.responseLength()));
        }
    }

    private static void exportElapsedNanos(State state, RequestLog log) {
        if (log.isAvailable(RequestLogProperty.RESPONSE_END_TIME)) {
            putBuiltInProperty(state, ELAPSED_NANOS, String.valueOf(log.totalDurationNanos()));
        }
    }

    private void exportTlsProperties(State state, RequestContext ctx) {
        final SSLSession s = ctx.sslSession();
        if (s != null) {
            if (builtInProperties.contains(TLS_SESSION_ID)) {
                final byte[] id = s.getId();
                if (id != null) {
                    putBuiltInProperty(state, TLS_SESSION_ID, lowerCasedBase16.encode(id));
                }
            }
            if (builtInProperties.contains(TLS_CIPHER)) {
                final String cs = s.getCipherSuite();
                if (cs != null) {
                    putBuiltInProperty(state, TLS_CIPHER, cs);
                }
            }
            if (builtInProperties.contains(TLS_PROTO)) {
                final String p = s.getProtocol();
                if (p != null) {
                    putBuiltInProperty(state, TLS_PROTO, p);
                }
            }
        }
    }

    private void exportRpcRequest(State state, RequestLog log) {
        if (!log.isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            return;
        }

        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            final RpcRequest rpcReq = (RpcRequest) requestContent;
            if (builtInProperties.contains(REQ_RPC_METHOD)) {
                putBuiltInProperty(state, REQ_RPC_METHOD, rpcReq.method());
            }
            if (builtInProperties.contains(REQ_RPC_PARAMS)) {
                putBuiltInProperty(state, REQ_RPC_PARAMS, String.valueOf(rpcReq.params()));
            }
        }
    }

    private void exportRpcResponse(State state, RequestLog log) {
        if (!log.isAvailable(RequestLogProperty.RESPONSE_CONTENT)) {
            return;
        }

        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            final RpcResponse rpcRes = (RpcResponse) responseContent;
            if (builtInProperties.contains(RES_RPC_RESULT) &&
                !rpcRes.isCompletedExceptionally()) {
                try {
                    putBuiltInProperty(state, RES_RPC_RESULT, String.valueOf(rpcRes.get()));
                } catch (Exception e) {
                    // Should never reach here because RpcResponse must be completed.
                    throw new Error(e);
                }
            }
        }
    }

    private void exportAttributes(State state) {
        if (attrs == null) {
            return;
        }

        assert state.attrValues != null;
        for (int i = 0; i < attrs.length; i++) {
            final ExportEntry<AttributeKey<?>> e = attrs[i];
            putOtherProperty(state, e, state.attrValues[i]);
        }
    }

    private void exportHttpRequestHeaders(State state, RequestLog log) {
        if (httpReqHeaders == null || !log.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
            return;
        }

        exportHttpHeaders(state, log.requestHeaders(), httpReqHeaders);
    }

    private void exportHttpResponseHeaders(State state, RequestLog log) {
        if (httpResHeaders == null || !log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            return;
        }

        exportHttpHeaders(state, log.responseHeaders(), httpResHeaders);
    }

    private static void exportHttpHeaders(State state, HttpHeaders headers,
                                          ExportEntry<AsciiString>[] requiredHeaderNames) {
        for (ExportEntry<AsciiString> e : requiredHeaderNames) {
            putOtherProperty(state, e, headers.get(e.key));
        }
    }

    private static void putBuiltInProperty(State state,
                                           BuiltInProperty prop, @Nullable String value) {
        if (value != null) {
            state.put(prop.key, value);
        }
    }

    private static void putOtherProperty(State state,
                                         ExportEntry<?> entry, @Nullable Object value) {
        if (value != null) {
            final String valueStr = entry.stringify(value);
            if (valueStr != null) {
                state.put(entry.exportKey, valueStr);
                return;
            }
        }

        // Remove the value if it exists already.
        state.remove(entry.exportKey);
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

    private State state(RequestContext ctx) {
        final State state;
        if (ctx instanceof ClientRequestContext) {
            state = ((ClientRequestContext) ctx).ownAttr(STATE);
        } else {
            state = ctx.attr(STATE);
        }

        if (state != null) {
            return state;
        }

        final State newState = new State(numAttrs);
        ctx.setAttr(STATE, newState);
        return newState;
    }

    private static final class State extends Object2ObjectOpenHashMap<String, String> {
        private static final long serialVersionUID = -7084248226635055988L;

        int availabilityStamp = -1;
        @Nullable
        final Object[] attrValues;

        State(int numAttrs) {
            attrValues = numAttrs != 0 ? new Object[numAttrs] : null;
        }
    }
}
