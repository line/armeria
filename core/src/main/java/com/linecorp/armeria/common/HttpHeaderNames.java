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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;

import io.netty.util.AsciiString;

/**
 * Standard HTTP header names.
 *
 * <p>These are all defined as lowercase to support HTTP/2 requirements while also not
 * violating HTTP/1.x requirements. New header names should always be lowercase.
 */
public final class HttpHeaderNames {

    // Pseudo-headers

    /**
     * {@code ":method"}.
     */
    public static final AsciiString METHOD = new AsciiString(":method");
    /**
     * {@code ":scheme"}.
     */
    public static final AsciiString SCHEME = new AsciiString(":scheme");
    /**
     * {@code ":authority"}.
     */
    public static final AsciiString AUTHORITY = new AsciiString(":authority");
    /**
     * {@code ":path"}.
     */
    public static final AsciiString PATH = new AsciiString(":path");
    /**
     * {@code ":status"}.
     */
    public static final AsciiString STATUS = new AsciiString(":status");

    // Ordinary headers

    /**
     * {@code "accept"}.
     */
    public static final AsciiString ACCEPT = new AsciiString("accept");
    /**
     * {@code "accept-charset"}.
     */
    public static final AsciiString ACCEPT_CHARSET = new AsciiString("accept-charset");
    /**
     * {@code "accept-encoding"}.
     */
    public static final AsciiString ACCEPT_ENCODING = new AsciiString("accept-encoding");
    /**
     * {@code "accept-language"}.
     */
    public static final AsciiString ACCEPT_LANGUAGE = new AsciiString("accept-language");
    /**
     * {@code "accept-ranges"}.
     */
    public static final AsciiString ACCEPT_RANGES = new AsciiString("accept-ranges");
    /**
     * {@code "accept-patch"}.
     */
    public static final AsciiString ACCEPT_PATCH = new AsciiString("accept-patch");
    /**
     * {@code "access-control-allow-credentials"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_CREDENTIALS =
            new AsciiString("access-control-allow-credentials");
    /**
     * {@code "access-control-allow-headers"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_HEADERS =
            new AsciiString("access-control-allow-headers");
    /**
     * {@code "access-control-allow-methods"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_METHODS =
            new AsciiString("access-control-allow-methods");
    /**
     * {@code "access-control-allow-origin"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_ORIGIN =
            new AsciiString("access-control-allow-origin");
    /**
     * {@code "access-control-expose-headers"}.
     */
    public static final AsciiString ACCESS_CONTROL_EXPOSE_HEADERS =
            new AsciiString("access-control-expose-headers");
    /**
     * {@code "access-control-max-age"}.
     */
    public static final AsciiString ACCESS_CONTROL_MAX_AGE = new AsciiString("access-control-max-age");
    /**
     * {@code "access-control-request-headers"}.
     */
    public static final AsciiString ACCESS_CONTROL_REQUEST_HEADERS =
            new AsciiString("access-control-request-headers");
    /**
     * {@code "access-control-request-method"}.
     */
    public static final AsciiString ACCESS_CONTROL_REQUEST_METHOD =
            new AsciiString("access-control-request-method");
    /**
     * {@code "age"}.
     */
    public static final AsciiString AGE = new AsciiString("age");
    /**
     * {@code "allow"}.
     */
    public static final AsciiString ALLOW = new AsciiString("allow");
    /**
     * {@code "authorization"}.
     */
    public static final AsciiString AUTHORIZATION = new AsciiString("authorization");
    /**
     * {@code "cache-control"}.
     */
    public static final AsciiString CACHE_CONTROL = new AsciiString("cache-control");
    /**
     * {@code "connection"}.
     */
    public static final AsciiString CONNECTION = new AsciiString("connection");
    /**
     * {@code "content-base"}.
     */
    public static final AsciiString CONTENT_BASE = new AsciiString("content-base");
    /**
     * {@code "content-disposition"}.
     */
    public static final AsciiString CONTENT_DISPOSITION = new AsciiString("content-disposition");
    /**
     * {@code "content-encoding"}.
     */
    public static final AsciiString CONTENT_ENCODING = new AsciiString("content-encoding");
    /**
     * {@code "content-language"}.
     */
    public static final AsciiString CONTENT_LANGUAGE = new AsciiString("content-language");
    /**
     * {@code "content-length"}.
     */
    public static final AsciiString CONTENT_LENGTH = new AsciiString("content-length");
    /**
     * {@code "content-location"}.
     */
    public static final AsciiString CONTENT_LOCATION = new AsciiString("content-location");
    /**
     * {@code "content-md5"}.
     */
    public static final AsciiString CONTENT_MD5 = new AsciiString("content-md5");
    /**
     * {@code "content-range"}.
     */
    public static final AsciiString CONTENT_RANGE = new AsciiString("content-range");
    /**
     * {@code "content-security-policy"}.
     */
    public static final AsciiString CONTENT_SECURITY_POLICY = new AsciiString("content-security-policy");
    /**
     * {@code "content-transfer-encoding"}.
     */
    public static final AsciiString CONTENT_TRANSFER_ENCODING = new AsciiString("content-transfer-encoding");
    /**
     * {@code "content-type"}.
     */
    public static final AsciiString CONTENT_TYPE = new AsciiString("content-type");
    /**
     * {@code "cookie"}.
     */
    public static final AsciiString COOKIE = new AsciiString("cookie");
    /**
     * {@code "date"}.
     */
    public static final AsciiString DATE = new AsciiString("date");
    /**
     * {@code "etag"}.
     */
    public static final AsciiString ETAG = new AsciiString("etag");
    /**
     * {@code "expect"}.
     */
    public static final AsciiString EXPECT = new AsciiString("expect");
    /**
     * {@code "expires"}.
     */
    public static final AsciiString EXPIRES = new AsciiString("expires");
    /**
     * {@code "from"}.
     */
    public static final AsciiString FROM = new AsciiString("from");
    /**
     * {@code "host"}.
     */
    public static final AsciiString HOST = new AsciiString("host");
    /**
     * {@code "if-match"}.
     */
    public static final AsciiString IF_MATCH = new AsciiString("if-match");
    /**
     * {@code "if-modified-since"}.
     */
    public static final AsciiString IF_MODIFIED_SINCE = new AsciiString("if-modified-since");
    /**
     * {@code "if-none-match"}.
     */
    public static final AsciiString IF_NONE_MATCH = new AsciiString("if-none-match");
    /**
     * {@code "if-range"}.
     */
    public static final AsciiString IF_RANGE = new AsciiString("if-range");
    /**
     * {@code "if-unmodified-since"}.
     */
    public static final AsciiString IF_UNMODIFIED_SINCE = new AsciiString("if-unmodified-since");
    /**
     * {@code "keep-alive"}.
     *
     * @deprecated Use {@link #CONNECTION} instead.
     */
    @Deprecated
    public static final AsciiString KEEP_ALIVE = new AsciiString("keep-alive");
    /**
     * {@code "last-modified"}.
     */
    public static final AsciiString LAST_MODIFIED = new AsciiString("last-modified");
    /**
     * {@code "link"}.
     */
    public static final AsciiString LINK = new AsciiString("link");
    /**
     * {@code "location"}.
     */
    public static final AsciiString LOCATION = new AsciiString("location");
    /**
     * {@code "max-forwards"}.
     */
    public static final AsciiString MAX_FORWARDS = new AsciiString("max-forwards");
    /**
     * {@code "origin"}.
     */
    public static final AsciiString ORIGIN = new AsciiString("origin");
    /**
     * {@code "pragma"}.
     */
    public static final AsciiString PRAGMA = new AsciiString("pragma");
    /**
     * {@code "prefer"}.
     */
    public static final AsciiString PREFER = new AsciiString("prefer");
    /**
     * {@code "proxy-authenticate"}.
     */
    public static final AsciiString PROXY_AUTHENTICATE = new AsciiString("proxy-authenticate");
    /**
     * {@code "proxy-authorization"}.
     */
    public static final AsciiString PROXY_AUTHORIZATION = new AsciiString("proxy-authorization");
    /**
     * {@code "proxy-connection"}.
     *
     * @deprecated Use {@link #CONNECTION} instead.
     */
    @Deprecated
    public static final AsciiString PROXY_CONNECTION = new AsciiString("proxy-connection");
    /**
     * {@code "range"}.
     */
    public static final AsciiString RANGE = new AsciiString("range");
    /**
     * {@code "referer"}.
     */
    public static final AsciiString REFERER = new AsciiString("referer");
    /**
     * {@code "retry-after"}.
     */
    public static final AsciiString RETRY_AFTER = new AsciiString("retry-after");
    /**
     * {@code "sec-websocket-key1"}.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY1 = new AsciiString("sec-websocket-key1");
    /**
     * {@code "sec-websocket-key2"}.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY2 = new AsciiString("sec-websocket-key2");
    /**
     * {@code "sec-websocket-location"}.
     */
    public static final AsciiString SEC_WEBSOCKET_LOCATION = new AsciiString("sec-websocket-location");
    /**
     * {@code "sec-websocket-origin"}.
     */
    public static final AsciiString SEC_WEBSOCKET_ORIGIN = new AsciiString("sec-websocket-origin");
    /**
     * {@code "sec-websocket-protocol"}.
     */
    public static final AsciiString SEC_WEBSOCKET_PROTOCOL = new AsciiString("sec-websocket-protocol");
    /**
     * {@code "sec-websocket-version"}.
     */
    public static final AsciiString SEC_WEBSOCKET_VERSION = new AsciiString("sec-websocket-version");
    /**
     * {@code "sec-websocket-key"}.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY = new AsciiString("sec-websocket-key");
    /**
     * {@code "sec-websocket-accept"}.
     */
    public static final AsciiString SEC_WEBSOCKET_ACCEPT = new AsciiString("sec-websocket-accept");
    /**
     * {@code "sec-websocket-protocol"}.
     */
    public static final AsciiString SEC_WEBSOCKET_EXTENSIONS = new AsciiString("sec-websocket-extensions");
    /**
     * {@code "server"}.
     */
    public static final AsciiString SERVER = new AsciiString("server");
    /**
     * {@code "set-cookie"}.
     */
    public static final AsciiString SET_COOKIE = new AsciiString("set-cookie");
    /**
     * {@code "set-cookie2"}.
     */
    public static final AsciiString SET_COOKIE2 = new AsciiString("set-cookie2");
    /**
     * {@code "te"}.
     */
    public static final AsciiString TE = new AsciiString("te");
    /**
     * {@code "trailer"}.
     */
    public static final AsciiString TRAILER = new AsciiString("trailer");
    /**
     * {@code "transfer-encoding"}.
     */
    public static final AsciiString TRANSFER_ENCODING = new AsciiString("transfer-encoding");
    /**
     * {@code "upgrade"}.
     */
    public static final AsciiString UPGRADE = new AsciiString("upgrade");
    /**
     * {@code "user-agent"}.
     */
    public static final AsciiString USER_AGENT = new AsciiString("user-agent");
    /**
     * {@code "vary"}.
     */
    public static final AsciiString VARY = new AsciiString("vary");
    /**
     * {@code "via"}.
     */
    public static final AsciiString VIA = new AsciiString("via");
    /**
     * {@code "warning"}.
     */
    public static final AsciiString WARNING = new AsciiString("warning");
    /**
     * {@code "websocket-location"}.
     */
    public static final AsciiString WEBSOCKET_LOCATION = new AsciiString("websocket-location");
    /**
     * {@code "websocket-origin"}.
     */
    public static final AsciiString WEBSOCKET_ORIGIN = new AsciiString("websocket-origin");
    /**
     * {@code "websocket-protocol"}.
     */
    public static final AsciiString WEBSOCKET_PROTOCOL = new AsciiString("websocket-protocol");
    /**
     * {@code "www-authenticate"}.
     */
    public static final AsciiString WWW_AUTHENTICATE = new AsciiString("www-authenticate");
    /**
     * {@code "x-frame-options"}.
     */
    public static final AsciiString X_FRAME_OPTIONS = new AsciiString("x-frame-options");

    private static final Map<String, AsciiString> map;

    static {
        final ImmutableMap.Builder<String, AsciiString> builder = ImmutableMap.builder();
        for (Field f : HttpHeaderNames.class.getDeclaredFields()) {
            final int m = f.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m) &&
                f.getType() == AsciiString.class) {
                final AsciiString name;
                try {
                    name = (AsciiString) f.get(null);
                } catch (Exception e) {
                    throw new Error(e);
                }
                builder.put(name.toString(), name);
            }
        }
        map = builder.build();
    }

    /**
     * Lower-cases and converts the specified header name into an {@link AsciiString}. If {@code name} is
     * a known header name, this method will return a pre-instantiated {@link AsciiString} to reduce
     * the allocation rate of {@link AsciiString}.
     */
    public static AsciiString of(String name) {
        name = Ascii.toLowerCase(requireNonNull(name, "name"));
        final AsciiString asciiName = map.get(name);
        return asciiName != null ? asciiName : new AsciiString(name);
    }

    private HttpHeaderNames() {}
}
