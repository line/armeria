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

    // Forked from Netty at 4875a2aad4c82204caedb2d121fb5ef03c09fe44

    // Pseudo-headers

    /**
     * {@code ":method"}.
     */
    public static final AsciiString METHOD = AsciiString.cached(":method");
    /**
     * {@code ":scheme"}.
     */
    public static final AsciiString SCHEME = AsciiString.cached(":scheme");
    /**
     * {@code ":authority"}.
     */
    public static final AsciiString AUTHORITY = AsciiString.cached(":authority");
    /**
     * {@code ":path"}.
     */
    public static final AsciiString PATH = AsciiString.cached(":path");
    /**
     * {@code ":status"}.
     */
    public static final AsciiString STATUS = AsciiString.cached(":status");

    // Ordinary headers

    /**
     * {@code "accept"}.
     */
    public static final AsciiString ACCEPT = AsciiString.cached("accept");
    /**
     * {@code "accept-charset"}.
     */
    public static final AsciiString ACCEPT_CHARSET = AsciiString.cached("accept-charset");
    /**
     * {@code "accept-encoding"}.
     */
    public static final AsciiString ACCEPT_ENCODING = AsciiString.cached("accept-encoding");
    /**
     * {@code "accept-language"}.
     */
    public static final AsciiString ACCEPT_LANGUAGE = AsciiString.cached("accept-language");
    /**
     * {@code "accept-ranges"}.
     */
    public static final AsciiString ACCEPT_RANGES = AsciiString.cached("accept-ranges");
    /**
     * {@code "accept-patch"}.
     */
    public static final AsciiString ACCEPT_PATCH = AsciiString.cached("accept-patch");
    /**
     * {@code "access-control-allow-credentials"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_CREDENTIALS =
            AsciiString.cached("access-control-allow-credentials");
    /**
     * {@code "access-control-allow-headers"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_HEADERS =
            AsciiString.cached("access-control-allow-headers");
    /**
     * {@code "access-control-allow-methods"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_METHODS =
            AsciiString.cached("access-control-allow-methods");
    /**
     * {@code "access-control-allow-origin"}.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_ORIGIN =
            AsciiString.cached("access-control-allow-origin");
    /**
     * {@code "access-control-expose-headers"}.
     */
    public static final AsciiString ACCESS_CONTROL_EXPOSE_HEADERS =
            AsciiString.cached("access-control-expose-headers");
    /**
     * {@code "access-control-max-age"}.
     */
    public static final AsciiString ACCESS_CONTROL_MAX_AGE = AsciiString.cached("access-control-max-age");
    /**
     * {@code "access-control-request-headers"}.
     */
    public static final AsciiString ACCESS_CONTROL_REQUEST_HEADERS =
            AsciiString.cached("access-control-request-headers");
    /**
     * {@code "access-control-request-method"}.
     */
    public static final AsciiString ACCESS_CONTROL_REQUEST_METHOD =
            AsciiString.cached("access-control-request-method");
    /**
     * {@code "age"}.
     */
    public static final AsciiString AGE = AsciiString.cached("age");
    /**
     * {@code "allow"}.
     */
    public static final AsciiString ALLOW = AsciiString.cached("allow");
    /**
     * {@code "authorization"}.
     */
    public static final AsciiString AUTHORIZATION = AsciiString.cached("authorization");
    /**
     * {@code "cache-control"}.
     */
    public static final AsciiString CACHE_CONTROL = AsciiString.cached("cache-control");
    /**
     * {@code "connection"}.
     */
    public static final AsciiString CONNECTION = AsciiString.cached("connection");
    /**
     * {@code "content-base"}.
     */
    public static final AsciiString CONTENT_BASE = AsciiString.cached("content-base");
    /**
     * {@code "content-disposition"}.
     */
    public static final AsciiString CONTENT_DISPOSITION = AsciiString.cached("content-disposition");
    /**
     * {@code "content-encoding"}.
     */
    public static final AsciiString CONTENT_ENCODING = AsciiString.cached("content-encoding");
    /**
     * {@code "content-language"}.
     */
    public static final AsciiString CONTENT_LANGUAGE = AsciiString.cached("content-language");
    /**
     * {@code "content-length"}.
     */
    public static final AsciiString CONTENT_LENGTH = AsciiString.cached("content-length");
    /**
     * {@code "content-location"}.
     */
    public static final AsciiString CONTENT_LOCATION = AsciiString.cached("content-location");
    /**
     * {@code "content-md5"}.
     */
    public static final AsciiString CONTENT_MD5 = AsciiString.cached("content-md5");
    /**
     * {@code "content-range"}.
     */
    public static final AsciiString CONTENT_RANGE = AsciiString.cached("content-range");
    /**
     * {@code "content-security-policy"}.
     */
    public static final AsciiString CONTENT_SECURITY_POLICY = AsciiString.cached("content-security-policy");
    /**
     * {@code "content-transfer-encoding"}.
     */
    public static final AsciiString CONTENT_TRANSFER_ENCODING = AsciiString.cached("content-transfer-encoding");
    /**
     * {@code "content-type"}.
     */
    public static final AsciiString CONTENT_TYPE = AsciiString.cached("content-type");
    /**
     * {@code "cookie"}.
     */
    public static final AsciiString COOKIE = AsciiString.cached("cookie");
    /**
     * {@code "date"}.
     */
    public static final AsciiString DATE = AsciiString.cached("date");
    /**
     * {@code "etag"}.
     */
    public static final AsciiString ETAG = AsciiString.cached("etag");
    /**
     * {@code "expect"}.
     */
    public static final AsciiString EXPECT = AsciiString.cached("expect");
    /**
     * {@code "expires"}.
     */
    public static final AsciiString EXPIRES = AsciiString.cached("expires");
    /**
     * {@code "forwarded"}.
     */
    public static final AsciiString FORWARDED = AsciiString.cached("forwarded");
    /**
     * {@code "from"}.
     */
    public static final AsciiString FROM = AsciiString.cached("from");
    /**
     * {@code "host"}.
     */
    public static final AsciiString HOST = AsciiString.cached("host");
    /**
     * {@code "if-match"}.
     */
    public static final AsciiString IF_MATCH = AsciiString.cached("if-match");
    /**
     * {@code "if-modified-since"}.
     */
    public static final AsciiString IF_MODIFIED_SINCE = AsciiString.cached("if-modified-since");
    /**
     * {@code "if-none-match"}.
     */
    public static final AsciiString IF_NONE_MATCH = AsciiString.cached("if-none-match");
    /**
     * {@code "if-range"}.
     */
    public static final AsciiString IF_RANGE = AsciiString.cached("if-range");
    /**
     * {@code "if-unmodified-since"}.
     */
    public static final AsciiString IF_UNMODIFIED_SINCE = AsciiString.cached("if-unmodified-since");
    /**
     * {@code "keep-alive"}.
     *
     * @deprecated Use {@link #CONNECTION}.
     */
    @Deprecated
    public static final AsciiString KEEP_ALIVE = AsciiString.cached("keep-alive");
    /**
     * {@code "last-event-id"}.
     */
    public static final AsciiString LAST_EVENT_ID = AsciiString.cached("last-event-id");
    /**
     * {@code "last-modified"}.
     */
    public static final AsciiString LAST_MODIFIED = AsciiString.cached("last-modified");
    /**
     * {@code "link"}.
     */
    public static final AsciiString LINK = AsciiString.cached("link");
    /**
     * {@code "location"}.
     */
    public static final AsciiString LOCATION = AsciiString.cached("location");
    /**
     * {@code "max-forwards"}.
     */
    public static final AsciiString MAX_FORWARDS = AsciiString.cached("max-forwards");
    /**
     * {@code "origin"}.
     */
    public static final AsciiString ORIGIN = AsciiString.cached("origin");
    /**
     * {@code "pragma"}.
     */
    public static final AsciiString PRAGMA = AsciiString.cached("pragma");
    /**
     * {@code "prefer"}.
     */
    public static final AsciiString PREFER = AsciiString.cached("prefer");
    /**
     * {@code "proxy-authenticate"}.
     */
    public static final AsciiString PROXY_AUTHENTICATE = AsciiString.cached("proxy-authenticate");
    /**
     * {@code "proxy-authorization"}.
     */
    public static final AsciiString PROXY_AUTHORIZATION = AsciiString.cached("proxy-authorization");
    /**
     * {@code "proxy-connection"}.
     *
     * @deprecated Use {@link #CONNECTION}.
     */
    @Deprecated
    public static final AsciiString PROXY_CONNECTION = AsciiString.cached("proxy-connection");
    /**
     * {@code "range"}.
     */
    public static final AsciiString RANGE = AsciiString.cached("range");
    /**
     * {@code "referer"}.
     */
    public static final AsciiString REFERER = AsciiString.cached("referer");
    /**
     * {@code "retry-after"}.
     */
    public static final AsciiString RETRY_AFTER = AsciiString.cached("retry-after");
    /**
     * {@code "sec-websocket-key1"}.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY1 = AsciiString.cached("sec-websocket-key1");
    /**
     * {@code "sec-websocket-key2"}.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY2 = AsciiString.cached("sec-websocket-key2");
    /**
     * {@code "sec-websocket-location"}.
     */
    public static final AsciiString SEC_WEBSOCKET_LOCATION = AsciiString.cached("sec-websocket-location");
    /**
     * {@code "sec-websocket-origin"}.
     */
    public static final AsciiString SEC_WEBSOCKET_ORIGIN = AsciiString.cached("sec-websocket-origin");
    /**
     * {@code "sec-websocket-protocol"}.
     */
    public static final AsciiString SEC_WEBSOCKET_PROTOCOL = AsciiString.cached("sec-websocket-protocol");
    /**
     * {@code "sec-websocket-version"}.
     */
    public static final AsciiString SEC_WEBSOCKET_VERSION = AsciiString.cached("sec-websocket-version");
    /**
     * {@code "sec-websocket-key"}.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY = AsciiString.cached("sec-websocket-key");
    /**
     * {@code "sec-websocket-accept"}.
     */
    public static final AsciiString SEC_WEBSOCKET_ACCEPT = AsciiString.cached("sec-websocket-accept");
    /**
     * {@code "sec-websocket-protocol"}.
     */
    public static final AsciiString SEC_WEBSOCKET_EXTENSIONS = AsciiString.cached("sec-websocket-extensions");
    /**
     * {@code "server"}.
     */
    public static final AsciiString SERVER = AsciiString.cached("server");
    /**
     * {@code "set-cookie"}.
     */
    public static final AsciiString SET_COOKIE = AsciiString.cached("set-cookie");
    /**
     * {@code "set-cookie2"}.
     */
    public static final AsciiString SET_COOKIE2 = AsciiString.cached("set-cookie2");
    /**
     * {@code "te"}.
     */
    public static final AsciiString TE = AsciiString.cached("te");
    /**
     * {@code "trailer"}.
     */
    public static final AsciiString TRAILER = AsciiString.cached("trailer");
    /**
     * {@code "transfer-encoding"}.
     */
    public static final AsciiString TRANSFER_ENCODING = AsciiString.cached("transfer-encoding");
    /**
     * {@code "upgrade"}.
     */
    public static final AsciiString UPGRADE = AsciiString.cached("upgrade");
    /**
     * {@code "user-agent"}.
     */
    public static final AsciiString USER_AGENT = AsciiString.cached("user-agent");
    /**
     * {@code "vary"}.
     */
    public static final AsciiString VARY = AsciiString.cached("vary");
    /**
     * {@code "via"}.
     */
    public static final AsciiString VIA = AsciiString.cached("via");
    /**
     * {@code "warning"}.
     */
    public static final AsciiString WARNING = AsciiString.cached("warning");
    /**
     * {@code "websocket-location"}.
     */
    public static final AsciiString WEBSOCKET_LOCATION = AsciiString.cached("websocket-location");
    /**
     * {@code "websocket-origin"}.
     */
    public static final AsciiString WEBSOCKET_ORIGIN = AsciiString.cached("websocket-origin");
    /**
     * {@code "websocket-protocol"}.
     */
    public static final AsciiString WEBSOCKET_PROTOCOL = AsciiString.cached("websocket-protocol");
    /**
     * {@code "www-authenticate"}.
     */
    public static final AsciiString WWW_AUTHENTICATE = AsciiString.cached("www-authenticate");
    /**
     * {@code "x-forwarded-for"}.
     */
    public static final AsciiString X_FORWARDED_FOR = AsciiString.cached("x-forwarded-for");
    /**
     * {@code "x-frame-options"}.
     */
    public static final AsciiString X_FRAME_OPTIONS = AsciiString.cached("x-frame-options");

    private static final Map<CharSequence, AsciiString> map;

    static {
        final ImmutableMap.Builder<CharSequence, AsciiString> builder = ImmutableMap.builder();
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
                builder.put(name, name);
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
    public static AsciiString of(CharSequence name) {
        if (name instanceof AsciiString) {
            return of((AsciiString) name);
        }

        final String lowerCased = Ascii.toLowerCase(requireNonNull(name, "name"));
        final AsciiString cached = map.get(lowerCased);
        return cached != null ? cached : AsciiString.cached(lowerCased);
    }

    /**
     * Lower-cases and converts the specified header name into an {@link AsciiString}. If {@code name} is
     * a known header name, this method will return a pre-instantiated {@link AsciiString} to reduce
     * the allocation rate of {@link AsciiString}.
     */
    public static AsciiString of(AsciiString name) {
        final AsciiString lowerCased = name.toLowerCase();
        final AsciiString cached = map.get(lowerCased);
        return cached != null ? cached : lowerCased;
    }

    private HttpHeaderNames() {}
}
