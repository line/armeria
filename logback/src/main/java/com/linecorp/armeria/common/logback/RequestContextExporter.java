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

package com.linecorp.armeria.common.logback;

import static com.linecorp.armeria.common.logback.BuiltInProperty.ELAPSED_NANOS;
import static com.linecorp.armeria.common.logback.BuiltInProperty.LOCAL_HOST;
import static com.linecorp.armeria.common.logback.BuiltInProperty.LOCAL_IP;
import static com.linecorp.armeria.common.logback.BuiltInProperty.LOCAL_PORT;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REMOTE_HOST;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REMOTE_IP;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REMOTE_PORT;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_AUTHORITY;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_CONTENT_LENGTH;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_DIRECTION;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_METHOD;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_PATH;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_QUERY;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_RPC_METHOD;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_RPC_PARAMS;
import static com.linecorp.armeria.common.logback.BuiltInProperty.RES_CONTENT_LENGTH;
import static com.linecorp.armeria.common.logback.BuiltInProperty.RES_RPC_RESULT;
import static com.linecorp.armeria.common.logback.BuiltInProperty.RES_STATUS_CODE;
import static com.linecorp.armeria.common.logback.BuiltInProperty.SCHEME;
import static com.linecorp.armeria.common.logback.BuiltInProperty.TLS_CIPHER;
import static com.linecorp.armeria.common.logback.BuiltInProperty.TLS_PROTO;
import static com.linecorp.armeria.common.logback.BuiltInProperty.TLS_SESSION_ID;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

final class RequestContextExporter {

    private static final Pattern PORT_443 = Pattern.compile(":0*443$");
    private static final Pattern PORT_80 = Pattern.compile(":0*80$");

    private static final BaseEncoding lowerCasedBase16 = BaseEncoding.base16().lowerCase();

    private final BuiltInProperties builtIns;
    private final ExportEntry<AttributeKey<?>>[] attrs;
    private final ExportEntry<AsciiString>[] httpReqHeaders;
    private final ExportEntry<AsciiString>[] httpResHeaders;

    @SuppressWarnings("unchecked")
    RequestContextExporter(Set<BuiltInProperty> builtIns,
                           Set<ExportEntry<AttributeKey<?>>> attrs,
                           Set<ExportEntry<AsciiString>> httpReqHeaders,
                           Set<ExportEntry<AsciiString>> httpResHeaders) {

        this.builtIns = new BuiltInProperties();
        builtIns.forEach(this.builtIns::add);

        if (!attrs.isEmpty()) {
            this.attrs = attrs.toArray(new ExportEntry[attrs.size()]);
        } else {
            this.attrs = null;
        }

        if (!httpReqHeaders.isEmpty()) {
            this.httpReqHeaders = httpReqHeaders.toArray(new ExportEntry[httpReqHeaders.size()]);
        } else {
            this.httpReqHeaders = null;
        }

        if (!httpResHeaders.isEmpty()) {
            this.httpResHeaders = httpResHeaders.toArray(new ExportEntry[httpResHeaders.size()]);
        } else {
            this.httpResHeaders = null;
        }
    }

    void export(Map<String, String> out, RequestContext ctx,
                @Nullable RequestLog log) {

        // Built-ins
        if (builtIns.containsAddresses()) {
            exportAddresses(out, ctx);
        }
        if (builtIns.contains(SCHEME)) {
            exportScheme(out, ctx, log);
        }
        if (builtIns.contains(REQ_DIRECTION)) {
            exportDirection(out, ctx);
        }
        if (builtIns.contains(REQ_AUTHORITY)) {
            exportAuthority(out, ctx, log);
        }
        if (builtIns.contains(REQ_PATH)) {
            exportPath(out, ctx);
        }
        if (builtIns.contains(REQ_QUERY)) {
            exportQuery(out, ctx);
        }
        if (builtIns.contains(REQ_METHOD)) {
            exportMethod(out, ctx);
        }
        if (builtIns.contains(REQ_CONTENT_LENGTH)) {
            exportRequestContentLength(out, log);
        }
        if (builtIns.contains(RES_STATUS_CODE)) {
            exportStatusCode(out, log);
        }
        if (builtIns.contains(RES_CONTENT_LENGTH)) {
            exportResponseContentLength(out, log);
        }
        if (builtIns.contains(ELAPSED_NANOS)) {
            exportElapsedNanos(out, log);
        }

        // SSL
        if (builtIns.containsSsl()) {
            exportTlsProperties(out, ctx);
        }

        // RPC
        if (builtIns.containsRpc()) {
            exportRpcRequest(out, log);
            exportRpcResponse(out, log);
        }

        // Attributes
        if (attrs != null) {
            exportAttributes(out, ctx);
        }

        // HTTP headers
        if (httpReqHeaders != null) {
            exportHttpRequestHeaders(out, ctx, log);
        }
        if (httpResHeaders != null) {
            exportHttpResponseHeaders(out, log);
        }
    }

    private void exportAddresses(Map<String, String> out, RequestContext ctx) {
        final InetSocketAddress raddr = ctx.remoteAddress();
        final InetSocketAddress laddr = ctx.localAddress();

        if (raddr != null) {
            if (builtIns.contains(REMOTE_HOST)) {
                out.put(REMOTE_HOST.mdcKey, raddr.getHostString());
            }
            if (builtIns.contains(REMOTE_IP)) {
                out.put(REMOTE_IP.mdcKey, raddr.getAddress().getHostAddress());
            }
            if (builtIns.contains(REMOTE_PORT)) {
                out.put(REMOTE_PORT.mdcKey, String.valueOf(raddr.getPort()));
            }
        }

        if (laddr != null) {
            if (builtIns.contains(LOCAL_HOST)) {
                out.put(LOCAL_HOST.mdcKey, laddr.getHostString());
            }
            if (builtIns.contains(LOCAL_IP)) {
                out.put(LOCAL_IP.mdcKey, laddr.getAddress().getHostAddress());
            }
            if (builtIns.contains(LOCAL_PORT)) {
                out.put(LOCAL_PORT.mdcKey, String.valueOf(laddr.getPort()));
            }
        }
    }

    private static void exportScheme(Map<String, String> out, RequestContext ctx, @Nullable RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.SCHEME)) {
            out.put(SCHEME.mdcKey, log.scheme().uriText());
        } else {
            out.put(SCHEME.mdcKey, "unknown+" + ctx.sessionProtocol().uriText());
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
        out.put(REQ_DIRECTION.mdcKey, d);
    }

    private static void exportAuthority(Map<String, String> out, RequestContext ctx, @Nullable RequestLog log) {
        final Set<RequestLogAvailability> availabilities = log.availabilities();
        if (availabilities.contains(RequestLogAvailability.REQUEST_HEADERS)) {
            final Object envelope = log.requestHeaders();
            if (envelope instanceof HttpHeaders) {
                final String authority = getAuthority(ctx, (HttpHeaders) envelope);
                if (authority != null) {
                    out.put(REQ_AUTHORITY.mdcKey, authority);
                    return;
                }
            }
        }

        final Request origReq = ctx.request();
        if (origReq instanceof HttpRequest) {
            final String authority = getAuthority(ctx, ((HttpRequest) origReq).headers());
            if (authority != null) {
                out.put(REQ_AUTHORITY.mdcKey, authority);
                return;
            }
        }

        if (log.isAvailable(RequestLogAvailability.REQUEST_START)) {
            String authority = log.host();
            if (authority != null) {
                final Channel ch = log.channel();
                final InetSocketAddress addr = (InetSocketAddress)
                        (ctx instanceof ServiceRequestContext ? ch.localAddress() : ch.remoteAddress());
                final int port = addr.getPort();

                if (ctx.sessionProtocol().defaultPort() != port) {
                    authority = authority + ':' + port;
                }
                out.put(REQ_AUTHORITY.mdcKey, authority);
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
            if (endpoint.isGroup()) {
                authority = endpoint.authority();
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

        out.put(REQ_AUTHORITY.mdcKey, authority);
    }

    private static String getAuthority(RequestContext ctx, HttpHeaders headers) {
        String authority = headers.authority();
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
        out.put(REQ_PATH.mdcKey, ctx.path());
    }

    private static void exportQuery(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_QUERY.mdcKey, ctx.query());
    }

    private static void exportMethod(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_METHOD.mdcKey, ctx.method().name());
    }

    private static void exportRequestContentLength(Map<String, String> out, @Nullable RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.REQUEST_END)) {
            out.put(REQ_CONTENT_LENGTH.mdcKey, String.valueOf(log.requestLength()));
        }
    }

    private static void exportStatusCode(Map<String, String> out, @Nullable RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
            final HttpStatus status = log.status();
            out.put(RES_STATUS_CODE.mdcKey, status != null ? status.codeAsText() : "-1");
        }
    }

    private static void exportResponseContentLength(Map<String, String> out, @Nullable RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.RESPONSE_END)) {
            out.put(RES_CONTENT_LENGTH.mdcKey, String.valueOf(log.responseLength()));
        }
    }

    private static void exportElapsedNanos(Map<String, String> out, @Nullable RequestLog log) {
        if (log.isAvailable(RequestLogAvailability.COMPLETE)) {
            out.put(ELAPSED_NANOS.mdcKey, String.valueOf(log.totalDurationNanos()));
        }
    }

    private void exportTlsProperties(Map<String, String> out, RequestContext ctx) {
        final SSLSession s = ctx.sslSession();
        if (s != null) {
            if (builtIns.contains(TLS_SESSION_ID)) {
                final byte[] id = s.getId();
                if (id != null) {
                    out.put(TLS_SESSION_ID.mdcKey, lowerCasedBase16.encode(id));
                }
            }
            if (builtIns.contains(TLS_CIPHER)) {
                final String cs = s.getCipherSuite();
                if (cs != null) {
                    out.put(TLS_CIPHER.mdcKey, cs);
                }
            }
            if (builtIns.contains(TLS_PROTO)) {
                final String p = s.getProtocol();
                if (p != null) {
                    out.put(TLS_PROTO.mdcKey, p);
                }
            }
        }
    }

    private void exportRpcRequest(Map<String, String> out, @Nullable RequestLog log) {
        if (!log.isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
            return;
        }

        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            final RpcRequest rpcReq = (RpcRequest) requestContent;
            if (builtIns.contains(REQ_RPC_METHOD)) {
                out.put(REQ_RPC_METHOD.mdcKey, rpcReq.method());
            }
            if (builtIns.contains(REQ_RPC_PARAMS)) {
                out.put(REQ_RPC_PARAMS.mdcKey, String.valueOf(rpcReq.params()));
            }
        }
    }

    private void exportRpcResponse(Map<String, String> out, @Nullable RequestLog log) {
        if (!log.isAvailable(RequestLogAvailability.RESPONSE_CONTENT)) {
            return;
        }

        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            final RpcResponse rpcRes = (RpcResponse) responseContent;
            if (builtIns.contains(RES_RPC_RESULT) &&
                !rpcRes.isCompletedExceptionally()) {
                try {
                    out.put(RES_RPC_RESULT.mdcKey, String.valueOf(rpcRes.get()));
                } catch (Exception e) {
                    // Should never reach here because RpcResponse must be completed.
                    throw new Error(e);
                }
            }
        }
    }

    private void exportAttributes(Map<String, String> out, RequestContext ctx) {
        for (ExportEntry<AttributeKey<?>> e : attrs) {
            final AttributeKey<?> attrKey = e.key;
            final String mdcKey = e.mdcKey;
            if (ctx.hasAttr(attrKey)) {
                final Object value = ctx.attr(attrKey).get();
                if (value != null) {
                    out.put(mdcKey, e.stringify(value));
                }
            }
        }
    }

    private void exportHttpRequestHeaders(Map<String, String> out, RequestContext ctx, RequestLog log) {
        if (!log.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return;
        }

        final Object requestEnvelope = log.requestHeaders();
        final HttpHeaders headers;
        if (requestEnvelope instanceof HttpHeaders) {
            headers = (HttpHeaders) requestEnvelope;
        } else if (ctx.request() instanceof HttpRequest) {
            headers = ((HttpRequest) ctx.request()).headers();
        } else {
            return;
        }

        exportHttpHeaders(out, headers, httpReqHeaders);
    }

    private void exportHttpResponseHeaders(Map<String, String> out, RequestLog log) {
        if (!log.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
            return;
        }

        final Object responseEnvelope = log.responseHeaders();
        if (responseEnvelope instanceof HttpHeaders) {
            exportHttpHeaders(out, (HttpHeaders) responseEnvelope, httpResHeaders);
        }
    }

    private static void exportHttpHeaders(Map<String, String> out, HttpHeaders headers,
                                          ExportEntry<AsciiString>[] requiredHeaderNames) {
        for (ExportEntry<AsciiString> e : requiredHeaderNames) {
            final String value = headers.get(e.key);
            final String mdcKey = e.mdcKey;
            if (value != null) {
                out.put(mdcKey, e.stringify(value));
            }
        }
    }

    static final class ExportEntry<T> {
        final T key;
        final String mdcKey;
        final Function<Object, String> stringifier;

        @SuppressWarnings("unchecked")
        ExportEntry(T key, String mdcKey, Function<?, ?> stringifier) {
            assert key != null;
            assert mdcKey != null;
            this.key = key;
            this.mdcKey = mdcKey;
            this.stringifier = (Function<Object, String>) stringifier;
        }

        String stringify(Object value) {
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
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            return key.equals(((ExportEntry<?>) o).key);
        }

        @Override
        public String toString() {
            return mdcKey + ':' + key;
        }
    }
}
