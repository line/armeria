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
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
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
                @Nullable RequestLog req, @Nullable ResponseLog res) {

        // Built-ins
        if (builtIns.containsAddresses()) {
            exportAddresses(out, ctx);
        }
        if (builtIns.contains(SCHEME)) {
            exportScheme(out, ctx, req);
        }
        if (builtIns.contains(REQ_DIRECTION)) {
            exportDirection(out, ctx);
        }
        if (builtIns.contains(REQ_AUTHORITY)) {
            exportAuthority(out, ctx, req);
        }
        if (builtIns.contains(REQ_PATH)) {
            exportPath(out, ctx);
        }
        if (builtIns.contains(REQ_METHOD)) {
            exportMethod(out, ctx);
        }
        if (builtIns.contains(REQ_CONTENT_LENGTH)) {
            exportRequestContentLength(out, req);
        }
        if (builtIns.contains(RES_STATUS_CODE)) {
            exportStatusCode(out, res);
        }
        if (builtIns.contains(RES_CONTENT_LENGTH)) {
            exportResponseContentLength(out, res);
        }
        if (builtIns.contains(ELAPSED_NANOS)) {
            exportElapsedNanos(out, req, res);
        }

        // SSL
        if (builtIns.containsSsl()) {
            exportTlsProperties(out, ctx);
        }

        // RPC
        if (builtIns.containsRpc()) {
            exportRpcRequest(out, req);
            exportRpcResponse(out, res);
        }

        // Attributes
        if (attrs != null) {
            exportAttributes(out, ctx);
        }

        // HTTP headers
        if (httpReqHeaders != null) {
            exportHttpRequestHeaders(out, ctx, req);
        }
        if (httpResHeaders != null) {
            exportHttpResponseHeaders(out, ctx, res);
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

    private static void exportScheme(Map<String, String> out, RequestContext ctx, @Nullable RequestLog req) {
        if (req != null) {
            out.put(SCHEME.mdcKey, req.scheme().uriText());
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

    private static void exportAuthority(Map<String, String> out, RequestContext ctx, @Nullable RequestLog req) {
        if (req != null) {
            String authority = req.host();
            if (authority != null) {
                final Channel ch = req.channel();
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

        final Request origReq = ctx.request();
        if (origReq instanceof HttpRequest) {
            String authority = ((HttpRequest) origReq).authority();
            if (authority != null) {
                final Pattern portPattern = ctx.sessionProtocol().isTls() ? PORT_443 : PORT_80;
                final Matcher m = portPattern.matcher(authority);
                if (m.find()) {
                    authority = authority.substring(0, m.start());
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

    private static void exportPath(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_PATH.mdcKey, ctx.path());
    }

    private static void exportMethod(Map<String, String> out, RequestContext ctx) {
        out.put(REQ_METHOD.mdcKey, ctx.method());
    }

    private static void exportRequestContentLength(Map<String, String> out, @Nullable RequestLog req) {
        if (req != null) {
            out.put(REQ_CONTENT_LENGTH.mdcKey, String.valueOf(req.contentLength()));
        }
    }

    private static void exportStatusCode(Map<String, String> out, @Nullable ResponseLog res) {
        if (res != null) {
            out.put(RES_STATUS_CODE.mdcKey, String.valueOf(res.statusCode()));
        }
    }

    private static void exportResponseContentLength(Map<String, String> out, @Nullable ResponseLog res) {
        if (res != null) {
            out.put(RES_CONTENT_LENGTH.mdcKey, String.valueOf(res.contentLength()));
        }
    }

    private static void exportElapsedNanos(Map<String, String> out, @Nullable RequestLog req,
                                           @Nullable ResponseLog res) {
        if (req != null && res != null) {
            out.put(ELAPSED_NANOS.mdcKey, String.valueOf(res.endTimeNanos() - req.startTimeNanos()));
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

    private void exportRpcRequest(Map<String, String> out, @Nullable RequestLog req) {
        if (req != null && req.hasAttr(RequestLog.RPC_REQUEST)) {
            RpcRequest rpcReq = req.attr(RequestLog.RPC_REQUEST).get();
            if (builtIns.contains(REQ_RPC_METHOD)) {
                out.put(REQ_RPC_METHOD.mdcKey, rpcReq.method());
            }
            if (builtIns.contains(REQ_RPC_PARAMS)) {
                out.put(REQ_RPC_PARAMS.mdcKey, String.valueOf(rpcReq.params()));
            }
        }
    }

    private void exportRpcResponse(Map<String, String> out, @Nullable ResponseLog res) {
        if (res != null && res.hasAttr(ResponseLog.RPC_RESPONSE)) {
            final RpcResponse rpcRes = res.attr(ResponseLog.RPC_RESPONSE).get();
            if (builtIns.contains(RES_RPC_RESULT)) {
                out.put(RES_RPC_RESULT.mdcKey, String.valueOf(rpcRes.toCompletableFuture().getNow(null)));
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

    private void exportHttpRequestHeaders(Map<String, String> out, RequestContext ctx, RequestLog req) {
        final HttpHeaders headers;
        if (req != null && req.hasAttr(RequestLog.HTTP_HEADERS)) {
            headers = req.attr(RequestLog.HTTP_HEADERS).get();
        } else if (ctx.request() instanceof HttpRequest) {
            headers = ((HttpRequest) ctx.request()).headers();
        } else {
            return;
        }

        exportHttpHeaders(out, headers, httpReqHeaders);
    }

    private void exportHttpResponseHeaders(Map<String, String> out, RequestContext ctx, ResponseLog res) {
        final HttpHeaders headers;
        if (res != null && res.hasAttr(ResponseLog.HTTP_HEADERS)) {
            headers = res.attr(ResponseLog.HTTP_HEADERS).get();
        } else if (ctx.request() instanceof HttpRequest) {
            headers = ((HttpRequest) ctx.request()).headers();
        } else {
            return;
        }

        exportHttpHeaders(out, headers, httpResHeaders);
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
