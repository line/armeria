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
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
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
 * Contains constant definitions for the HTTP header field names.
 *
 * <p>All header names in this class are defined in lowercase to support HTTP/2 requirements while
 * also not violating HTTP/1 requirements.</p>
 */
public final class HttpHeaderNames {

    // Forked from Guava 27.1 at 8e174e76971449665658a800af6dd350806cc934
    // Changes:
    // - Added pseudo headers
    // - Added Accept-Patch
    // - Added Content-Base
    // - Added Prefer
    // - Removed the ancient CSP headers
    //   - X-Content-Security-Policy
    //   - X-Content-Security-Policy-Report-Only
    //   - X-WebKit-CSP
    //   - X-WebKit-CSP-Report-Only
    // - Removed Sec-Metadata headers (too early to add)
    //   - Sec-Fetch-Dest
    //   - Sec-Fetch-Mode
    //   - Sec-Fetch-Site
    //   - Sec-Fetch-User
    //   - Sec-Metadata

    // Pseudo-headers

    /**
     * The HTTP {@code ":method"} pseudo header field name.
     */
    public static final AsciiString METHOD = create(":method");
    /**
     * The HTTP {@code ":scheme"} pseudo header field name.
     */
    public static final AsciiString SCHEME = create(":scheme");
    /**
     * The HTTP {@code ":authority"} pseudo header field name.
     */
    public static final AsciiString AUTHORITY = create(":authority");
    /**
     * The HTTP {@code ":path"} pseudo header field name.
     */
    public static final AsciiString PATH = create(":path");
    /**
     * The HTTP {@code ":status"} pseudo header field name.
     */
    public static final AsciiString STATUS = create(":status");

    // HTTP Request and Response header fields

    /**
     * The HTTP {@code "Cache-Control"} header field name.
     */
    public static final AsciiString CACHE_CONTROL = create("Cache-Control");
    /**
     * The HTTP {@code "Content-Length"} header field name.
     */
    public static final AsciiString CONTENT_LENGTH = create("Content-Length");
    /**
     * The HTTP {@code "Content-Type"} header field name.
     */
    public static final AsciiString CONTENT_TYPE = create("Content-Type");
    /**
     * The HTTP {@code "Date"} header field name.
     */
    public static final AsciiString DATE = create("Date");
    /**
     * The HTTP {@code "Pragma"} header field name.
     */
    public static final AsciiString PRAGMA = create("Pragma");
    /**
     * The HTTP {@code "Via"} header field name.
     */
    public static final AsciiString VIA = create("Via");
    /**
     * The HTTP {@code "Warning"} header field name.
     */
    public static final AsciiString WARNING = create("Warning");

    // HTTP Request header fields

    /**
     * The HTTP {@code "Accept"} header field name.
     */
    public static final AsciiString ACCEPT = create("Accept");
    /**
     * The HTTP {@code "Accept-Charset"} header field name.
     */
    public static final AsciiString ACCEPT_CHARSET = create("Accept-Charset");
    /**
     * The HTTP {@code "Accept-Encoding"} header field name.
     */
    public static final AsciiString ACCEPT_ENCODING = create("Accept-Encoding");
    /**
     * The HTTP {@code "Accept-Language"} header field name.
     */
    public static final AsciiString ACCEPT_LANGUAGE = create("Accept-Language");
    /**
     * The HTTP {@code "Access-Control-Request-Headers"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_REQUEST_HEADERS = create("Access-Control-Request-Headers");
    /**
     * The HTTP {@code "Access-Control-Request-Method"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_REQUEST_METHOD = create("Access-Control-Request-Method");
    /**
     * The HTTP {@code "Authorization"} header field name.
     */
    public static final AsciiString AUTHORIZATION = create("Authorization");
    /**
     * The HTTP {@code "Connection"} header field name.
     */
    public static final AsciiString CONNECTION = create("Connection");
    /**
     * The HTTP {@code "Cookie"} header field name.
     */
    public static final AsciiString COOKIE = create("Cookie");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc8470">{@code "Early-Data"}</a> header field
     * name.
     */
    public static final AsciiString EARLY_DATA = create("Early-Data");
    /**
     * The HTTP {@code "Expect"} header field name.
     */
    public static final AsciiString EXPECT = create("Expect");
    /**
     * The HTTP {@code "From"} header field name.
     */
    public static final AsciiString FROM = create("From");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc7239">{@code "Forwarded"}</a> header field name.
     */
    public static final AsciiString FORWARDED = create("Forwarded");
    /**
     * The HTTP {@code "Follow-Only-When-Prerender-Shown"} header field name.
     */
    public static final AsciiString FOLLOW_ONLY_WHEN_PRERENDER_SHOWN =
            create("Follow-Only-When-Prerender-Shown");
    /**
     * The HTTP {@code "Host"} header field name.
     */
    public static final AsciiString HOST = create("Host");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc7540#section-3.2.1">{@code "HTTP2-Settings"}
     * </a> header field name.
     */
    public static final AsciiString HTTP2_SETTINGS = create("HTTP2-Settings");
    /**
     * The HTTP {@code "If-Match"} header field name.
     */
    public static final AsciiString IF_MATCH = create("If-Match");
    /**
     * The HTTP {@code "If-Modified-Since"} header field name.
     */
    public static final AsciiString IF_MODIFIED_SINCE = create("If-Modified-Since");
    /**
     * The HTTP {@code "If-None-Match"} header field name.
     */
    public static final AsciiString IF_NONE_MATCH = create("If-None-Match");
    /**
     * The HTTP {@code "If-Range"} header field name.
     */
    public static final AsciiString IF_RANGE = create("If-Range");
    /**
     * The HTTP {@code "If-Unmodified-Since"} header field name.
     */
    public static final AsciiString IF_UNMODIFIED_SINCE = create("If-Unmodified-Since");
    /**
     * The HTTP {@code "Last-Event-ID"} header field name.
     */
    public static final AsciiString LAST_EVENT_ID = create("Last-Event-ID");
    /**
     * The HTTP {@code "Max-Forwards"} header field name.
     */
    public static final AsciiString MAX_FORWARDS = create("Max-Forwards");
    /**
     * The HTTP {@code "Origin"} header field name.
     */
    public static final AsciiString ORIGIN = create("Origin");
    /**
     * The HTTP {@code "Prefer"} header field name.
     */
    public static final AsciiString PREFER = create("Prefer");
    /**
     * The HTTP {@code "Proxy-Authorization"} header field name.
     */
    public static final AsciiString PROXY_AUTHORIZATION = create("Proxy-Authorization");
    /**
     * The HTTP {@code "Range"} header field name.
     */
    public static final AsciiString RANGE = create("Range");
    /**
     * The HTTP {@code "Referer"} header field name.
     */
    public static final AsciiString REFERER = create("Referer");
    /**
     * The HTTP <a href="https://www.w3.org/TR/referrer-policy/">{@code "Referrer-Policy"}</a> header
     * field name.
     */
    public static final AsciiString REFERRER_POLICY = create("Referrer-Policy");

    /**
     * The HTTP <a href="https://www.w3.org/TR/service-workers/#update-algorithm">{@code
     * Service-Worker}</a> header field name.
     */
    public static final AsciiString SERVICE_WORKER = create("Service-Worker");
    /**
     * The HTTP {@code "TE"} header field name.
     */
    public static final AsciiString TE = create("TE");
    /**
     * The HTTP {@code "Upgrade"} header field name.
     */
    public static final AsciiString UPGRADE = create("Upgrade");
    /**
     * The HTTP {@code "User-Agent"} header field name.
     */
    public static final AsciiString USER_AGENT = create("User-Agent");

    // HTTP Response header fields

    /**
     * The HTTP {@code "Accept-Ranges"} header field name.
     */
    public static final AsciiString ACCEPT_RANGES = create("Accept-Ranges");
    /**
     * The HTTP {@code "Accept-Patch"} header field name.
     */
    public static final AsciiString ACCEPT_PATCH = create("Accept-Patch");
    /**
     * The HTTP {@code "Access-Control-Allow-Headers"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_HEADERS = create("Access-Control-Allow-Headers");
    /**
     * The HTTP {@code "Access-Control-Allow-Methods"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_METHODS = create("Access-Control-Allow-Methods");
    /**
     * The HTTP {@code "Access-Control-Allow-Origin"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_ORIGIN = create("Access-Control-Allow-Origin");
    /**
     * The HTTP {@code "Access-Control-Allow-Credentials"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_CREDENTIALS =
            create("Access-Control-Allow-Credentials");
    /**
     * The HTTP {@code "Access-Control-Expose-Headers"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_EXPOSE_HEADERS = create("Access-Control-Expose-Headers");
    /**
     * The HTTP {@code "Access-Control-Max-Age"} header field name.
     */
    public static final AsciiString ACCESS_CONTROL_MAX_AGE = create("Access-Control-Max-Age");
    /**
     * The HTTP {@code "Age"} header field name.
     */
    public static final AsciiString AGE = create("Age");
    /**
     * The HTTP {@code "Allow"} header field name.
     */
    public static final AsciiString ALLOW = create("Allow");
    /**
     * The HTTP {@code "Content-Base"} header field name.
     */
    public static final AsciiString CONTENT_BASE = create("Content-Base");
    /**
     * The HTTP {@code "Content-Disposition"} header field name.
     */
    public static final AsciiString CONTENT_DISPOSITION = create("Content-Disposition");
    /**
     * The HTTP {@code "Content-Encoding"} header field name.
     */
    public static final AsciiString CONTENT_ENCODING = create("Content-Encoding");
    /**
     * The HTTP {@code "Content-Language"} header field name.
     */
    public static final AsciiString CONTENT_LANGUAGE = create("Content-Language");
    /**
     * The HTTP {@code "Content-Location"} header field name.
     */
    public static final AsciiString CONTENT_LOCATION = create("Content-Location");
    /**
     * The HTTP {@code "Content-MD5"} header field name.
     */
    public static final AsciiString CONTENT_MD5 = create("Content-MD5");
    /**
     * The HTTP {@code "Content-Range"} header field name.
     */
    public static final AsciiString CONTENT_RANGE = create("Content-Range");
    /**
     * The HTTP <a href="https://w3.org/TR/CSP/#content-security-policy-header-field">{@code
     * Content-Security-Policy}</a> header field name.
     */
    public static final AsciiString CONTENT_SECURITY_POLICY = create("Content-Security-Policy");
    /**
     * The HTTP <a href="https://w3.org/TR/CSP/#content-security-policy-report-only-header-field">
     * {@code "Content-Security-Policy-Report-Only"}</a> header field name.
     */
    public static final AsciiString CONTENT_SECURITY_POLICY_REPORT_ONLY =
            create("Content-Security-Policy-Report-Only");
    /**
     * The HTTP {@code "ETag"} header field name.
     */
    public static final AsciiString ETAG = create("ETag");
    /**
     * The HTTP {@code "Expires"} header field name.
     */
    public static final AsciiString EXPIRES = create("Expires");
    /**
     * The HTTP {@code "Last-Modified"} header field name.
     */
    public static final AsciiString LAST_MODIFIED = create("Last-Modified");
    /**
     * The HTTP {@code "Link"} header field name.
     */
    public static final AsciiString LINK = create("Link");
    /**
     * The HTTP {@code "Location"} header field name.
     */
    public static final AsciiString LOCATION = create("Location");
    /**
     * The HTTP <a href="https://googlechrome.github.io/OriginTrials/#header">{@code "Origin-Trial"}</a>
     * header field name.
     */
    public static final AsciiString ORIGIN_TRIAL = create("Origin-Trial");
    /**
     * The HTTP {@code "P3P"} header field name. Limited browser support.
     */
    public static final AsciiString P3P = create("P3P");
    /**
     * The HTTP {@code "Proxy-Authenticate"} header field name.
     */
    public static final AsciiString PROXY_AUTHENTICATE = create("Proxy-Authenticate");
    /**
     * The HTTP {@code "Refresh"} header field name. Non-standard header supported by most browsers.
     */
    public static final AsciiString REFRESH = create("Refresh");
    /**
     * The HTTP <a href="https://www.w3.org/TR/reporting/">{@code "Report-To"}</a> header field name.
     */
    public static final AsciiString REPORT_TO = create("Report-To");
    /**
     * The HTTP {@code "Retry-After"} header field name.
     */
    public static final AsciiString RETRY_AFTER = create("Retry-After");
    /**
     * The HTTP {@code "Server"} header field name.
     */
    public static final AsciiString SERVER = create("Server");
    /**
     * The HTTP <a href="https://www.w3.org/TR/server-timing/">{@code "Server-Timing"}</a> header field
     * name.
     */
    public static final AsciiString SERVER_TIMING = create("Server-Timing");
    /**
     * The HTTP <a href="https://www.w3.org/TR/service-workers/#update-algorithm">{@code
     * Service-Worker-Allowed}</a> header field name.
     */
    public static final AsciiString SERVICE_WORKER_ALLOWED = create("Service-Worker-Allowed");
    /**
     * The HTTP {@code "Set-Cookie"} header field name.
     */
    public static final AsciiString SET_COOKIE = create("Set-Cookie");
    /**
     * The HTTP {@code "Set-Cookie2"} header field name.
     */
    public static final AsciiString SET_COOKIE2 = create("Set-Cookie2");

    /**
     * The HTTP <a href="https://goo.gl/Dxx19N">{@code "SourceMap"}</a> header field name.
     */
    public static final AsciiString SOURCE_MAP = create("SourceMap");

    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc6797#section-6.1">{@code
     * Strict-Transport-Security}</a> header field name.
     */
    public static final AsciiString STRICT_TRANSPORT_SECURITY = create("Strict-Transport-Security");
    /**
     * The HTTP <a href="https://www.w3.org/TR/resource-timing/#cross-origin-resources">{@code
     * Timing-Allow-Origin}</a> header field name.
     */
    public static final AsciiString TIMING_ALLOW_ORIGIN = create("Timing-Allow-Origin");
    /**
     * The HTTP {@code "Trailer"} header field name.
     */
    public static final AsciiString TRAILER = create("Trailer");
    /**
     * The HTTP {@code "Transfer-Encoding"} header field name.
     */
    public static final AsciiString TRANSFER_ENCODING = create("Transfer-Encoding");
    /**
     * The HTTP {@code "Vary"} header field name.
     */
    public static final AsciiString VARY = create("Vary");
    /**
     * The HTTP {@code "WWW-Authenticate"} header field name.
     */
    public static final AsciiString WWW_AUTHENTICATE = create("WWW-Authenticate");

    // Common, non-standard HTTP header fields

    /**
     * The HTTP {@code "DNT"} header field name.
     */
    public static final AsciiString DNT = create("DNT");
    /**
     * The HTTP {@code "X-Content-Type-Options"} header field name.
     */
    public static final AsciiString X_CONTENT_TYPE_OPTIONS = create("X-Content-Type-Options");
    /**
     * The HTTP {@code "X-Do-Not-Track"} header field name.
     */
    public static final AsciiString X_DO_NOT_TRACK = create("X-Do-Not-Track");
    /**
     * The HTTP {@code "X-Forwarded-For"} header field name (superseded by {@code "Forwarded"}).
     */
    public static final AsciiString X_FORWARDED_FOR = create("X-Forwarded-For");
    /**
     * The HTTP {@code "X-Forwarded-Proto"} header field name.
     */
    public static final AsciiString X_FORWARDED_PROTO = create("X-Forwarded-Proto");
    /**
     * The HTTP <a href="https://goo.gl/lQirAH">{@code "X-Forwarded-Host"}</a> header field name.
     */
    public static final AsciiString X_FORWARDED_HOST = create("X-Forwarded-Host");
    /**
     * The HTTP <a href="https://goo.gl/YtV2at">{@code "X-Forwarded-Port"}</a> header field name.
     */
    public static final AsciiString X_FORWARDED_PORT = create("X-Forwarded-Port");
    /**
     * The HTTP {@code "X-Frame-Options"} header field name.
     */
    public static final AsciiString X_FRAME_OPTIONS = create("X-Frame-Options");
    /**
     * The HTTP {@code "X-Powered-By"} header field name.
     */
    public static final AsciiString X_POWERED_BY = create("X-Powered-By");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc7469">{@code
     * Public-Key-Pins}</a> header field name.
     */
    public static final AsciiString PUBLIC_KEY_PINS = create("Public-Key-Pins");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc7469">{@code
     * Public-Key-Pins-Report-Only}</a> header field name.
     */
    public static final AsciiString PUBLIC_KEY_PINS_REPORT_ONLY = create("Public-Key-Pins-Report-Only");
    /**
     * The HTTP {@code "X-Requested-With"} header field name.
     */
    public static final AsciiString X_REQUESTED_WITH = create("X-Requested-With");
    /**
     * The HTTP {@code "X-User-IP"} header field name.
     */
    public static final AsciiString X_USER_IP = create("X-User-IP");
    /**
     * The HTTP <a href="https://goo.gl/VKpXxa">{@code "X-Download-Options"}</a> header field name.
     *
     * <p>When the new X-Download-Options header is present with the value {@code "noopen"}, the user is
     * prevented from opening a file download directly; instead, they must first save the file
     * locally.
     */
    public static final AsciiString X_DOWNLOAD_OPTIONS = create("X-Download-Options");
    /**
     * The HTTP {@code "X-XSS-Protection"} header field name.
     */
    public static final AsciiString X_XSS_PROTECTION = create("X-XSS-Protection");
    /**
     * The HTTP <a
     * href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-DNS-Prefetch-Control">{@code
     * X-DNS-Prefetch-Control}</a> header controls DNS prefetch behavior. Value can be "on" or "off".
     * By default, DNS prefetching is "on" for HTTP pages and "off" for HTTPS pages.
     */
    public static final AsciiString X_DNS_PREFETCH_CONTROL = create("X-DNS-Prefetch-Control");
    /**
     * The HTTP <a href="https://html.spec.whatwg.org/multipage/semantics.html#hyperlink-auditing">
     * {@code "Ping-From"}</a> header field name.
     */
    public static final AsciiString PING_FROM = create("Ping-From");
    /**
     * The HTTP <a href="https://html.spec.whatwg.org/multipage/semantics.html#hyperlink-auditing">
     * {@code "Ping-To"}</a> header field name.
     */
    public static final AsciiString PING_TO = create("Ping-To");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/rfc8473">{@code
     * Sec-Token-Binding}</a> header field name.
     */
    public static final AsciiString SEC_TOKEN_BINDING = create("Sec-Token-Binding");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/draft-ietf-tokbind-ttrp">{@code
     * Sec-Provided-Token-Binding-ID}</a> header field name.
     */
    public static final AsciiString SEC_PROVIDED_TOKEN_BINDING_ID = create("Sec-Provided-Token-Binding-ID");
    /**
     * The HTTP <a href="https://tools.ietf.org/html/draft-ietf-tokbind-ttrp">{@code
     * Sec-Referred-Token-Binding-ID}</a> header field name.
     */
    public static final AsciiString SEC_REFERRED_TOKEN_BINDING_ID = create("Sec-Referred-Token-Binding-ID");

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
     * Lower-cases and converts the specified header name into an {@link AsciiString}. If {@code "name"} is
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
     * Lower-cases and converts the specified header name into an {@link AsciiString}. If {@code "name"} is
     * a known header name, this method will return a pre-instantiated {@link AsciiString} to reduce
     * the allocation rate of {@link AsciiString}.
     */
    public static AsciiString of(AsciiString name) {
        final AsciiString lowerCased = name.toLowerCase();
        final AsciiString cached = map.get(lowerCased);
        return cached != null ? cached : lowerCased;
    }

    private static AsciiString create(String name) {
        return AsciiString.cached(Ascii.toLowerCase(name));
    }

    private HttpHeaderNames() {}
}
