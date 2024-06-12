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
import java.util.BitSet;
import java.util.Map;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.math.IntMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * Contains constant definitions for the HTTP header field names.
 *
 * <p>All header names in this class are defined in lowercase to support HTTP/2 requirements while
 * also not violating HTTP/1 requirements.</p>
 */
public final class HttpHeaderNames {

    // Forked from Guava 31.0 at 7396bab41807702f2ce94517d0f58b8e52f603f8
    // Changes:
    // - Added pseudo headers
    // - Added Accept-Patch
    // - Added Content-Base
    // - Added Git-Protocol
    // - Added Prefer
    // - Removed the ancient CSP headers
    //   - X-Content-Security-Policy
    //   - X-Content-Security-Policy-Report-Only
    //   - X-WebKit-CSP
    //   - X-WebKit-CSP-Report-Only
    // - Removed Sec-Metadata headers (too early to add)
    //   - Sec-CH-Prefers-Color-Scheme

    private static final BitSet PROHIBITED_NAME_CHARS;
    private static final String[] PROHIBITED_NAME_CHAR_NAMES;
    private static final byte LAST_PROHIBITED_NAME_CHAR;

    @Nullable
    private static ImmutableMap.Builder<AsciiString, String> inverseMapBuilder = ImmutableMap.builder();

    static {
        PROHIBITED_NAME_CHARS = new BitSet();
        PROHIBITED_NAME_CHARS.set(0);
        PROHIBITED_NAME_CHARS.set('\t');
        PROHIBITED_NAME_CHARS.set('\n');
        PROHIBITED_NAME_CHARS.set(0xB);
        PROHIBITED_NAME_CHARS.set('\f');
        PROHIBITED_NAME_CHARS.set('\r');
        PROHIBITED_NAME_CHARS.set(' ');
        PROHIBITED_NAME_CHARS.set(',');
        PROHIBITED_NAME_CHARS.set(':');
        PROHIBITED_NAME_CHARS.set(';');
        PROHIBITED_NAME_CHARS.set('=');
        LAST_PROHIBITED_NAME_CHAR = (byte) (PROHIBITED_NAME_CHARS.size() - 1);

        PROHIBITED_NAME_CHAR_NAMES = new String[PROHIBITED_NAME_CHARS.size()];
        PROHIBITED_NAME_CHAR_NAMES[0] = "<NUL>";
        PROHIBITED_NAME_CHAR_NAMES['\t'] = "<TAB>";
        PROHIBITED_NAME_CHAR_NAMES['\n'] = "<LF>";
        PROHIBITED_NAME_CHAR_NAMES[0xB] = "<VT>";
        PROHIBITED_NAME_CHAR_NAMES['\f'] = "<FF>";
        PROHIBITED_NAME_CHAR_NAMES['\r'] = "<CR>";
        PROHIBITED_NAME_CHAR_NAMES[' '] = "<SP>";
        PROHIBITED_NAME_CHAR_NAMES[','] = ",";
        PROHIBITED_NAME_CHAR_NAMES[':'] = ":";
        PROHIBITED_NAME_CHAR_NAMES[';'] = ";";
        PROHIBITED_NAME_CHAR_NAMES['='] = "=";
    }

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
    /**
     * The HTTP {@code ":protocol"} pseudo header field name.
     *
     * @see <a href="https://datatracker.ietf.org/doc/rfc8441/">RFC 8441: Bootstrapping WebSockets with HTTP/2</a>
     */
    public static final AsciiString PROTOCOL = create(":protocol");

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
     * The HTTP <a href="https://wicg.github.io/private-network-access/#headers">{@code
     * Access-Control-Allow-Private-Network}</a> header field name.
     */
    public static final AsciiString ACCESS_CONTROL_ALLOW_PRIVATE_NETWORK =
            create("Access-Control-Allow-Private-Network");
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
     * The HTTP <a href="https://fetch.spec.whatwg.org/#cross-origin-resource-policy-header">{@code
     * Cross-Origin-Resource-Policy}</a> header field name.
     */
    public static final AsciiString CROSS_ORIGIN_RESOURCE_POLICY = create("Cross-Origin-Resource-Policy");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/rfc8470/">{@code "Early-Data"}</a> header field
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
     * The HTTP <a href="https://datatracker.ietf.org/doc/rfc7239/">{@code "Forwarded"}</a> header field name.
     */
    public static final AsciiString FORWARDED = create("Forwarded");
    /**
     * The HTTP {@code "Follow-Only-When-Prerender-Shown"} header field name.
     */
    public static final AsciiString FOLLOW_ONLY_WHEN_PRERENDER_SHOWN =
            create("Follow-Only-When-Prerender-Shown");
    /**
     * The HTTP {@code "Git-Protocol"} header field name, as described in
     * <a href="https://git-scm.com/docs/protocol-v2#_http_transport">HTTP Transport</a>.
     */
    @UnstableApi
    public static final AsciiString GIT_PROTOCOL = create("Git-Protocol");
    /**
     * The HTTP {@code "Host"} header field name.
     */
    public static final AsciiString HOST = create("Host");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-3.2.1">{@code "HTTP2-Settings"}
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
     * The HTTP <a href="https://github.com/WICG/origin-isolation">{@code Origin-Isolation}</a> header
     * field name.
     */
    public static final AsciiString ORIGIN_ISOLATION = create("Origin-Isolation");
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
     * The HTTP <a href="https://w3c.github.io/webappsec-upgrade-insecure-requests/#preference">{@code
     * Upgrade-Insecure-Requests}</a> header field name.
     */
    public static final AsciiString UPGRADE_INSECURE_REQUESTS = create("Upgrade-Insecure-Requests");
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
     * The HTTP/MIME {@code "Content-Description"} header field name.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-6.2">RFC 1521:
     * MIME Part One: Optional Content-Description Header Field</a>
     */
    public static final AsciiString CONTENT_DESCRIPTION = create("Content-Description");
    /**
     * The HTTP/MIME {@code "Content-Disposition"} header field name.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc2183">RFC 2183:
     * Communicating Presentation Information in Internet Messages: The Content-Disposition Header Field</a>
     */
    public static final AsciiString CONTENT_DISPOSITION = create("Content-Disposition");
    /**
     * The HTTP {@code "Content-Encoding"} header field name.
     */
    public static final AsciiString CONTENT_ENCODING = create("Content-Encoding");
    /**
     * The HTTP/MIME {@code "Content-ID"} header field name.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-6.1">RFC 1521:
     * MIME Part One: Optional Content-ID Header Field</a>
     */
    public static final AsciiString CONTENT_ID = create("Content-ID");
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
     * The HTTP/MIME {@code "Content-Transfer-Encoding"} header field name.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-5">RFC 1521:
     * MIME Part One: The Content-Transfer-Encoding Header Field</a>
     */
    public static final AsciiString CONTENT_TRANSFER_ENCODING = create("Content-Transfer-Encoding");
    /**
     * The HTTP <a href="https://wicg.github.io/cross-origin-embedder-policy/#COEP">{@code
     * Cross-Origin-Embedder-Policy}</a> header field name.
     */
    public static final AsciiString CROSS_ORIGIN_EMBEDDER_POLICY = create("Cross-Origin-Embedder-Policy");
    /**
     * The HTTP <a href="https://wicg.github.io/cross-origin-embedder-policy/#COEP-RO">{@code
     * Cross-Origin-Embedder-Policy-Report-Only}</a> header field name.
     */
    public static final AsciiString CROSS_ORIGIN_EMBEDDER_POLICY_REPORT_ONLY =
            create("Cross-Origin-Embedder-Policy-Report-Only");
    /**
     * The HTTP Cross-Origin-Opener-Policy header field name.
     */
    public static final AsciiString CROSS_ORIGIN_OPENER_POLICY = create("Cross-Origin-Opener-Policy");
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
     * The HTTP {@code Keep-Alive} header field name.
     */
    public static final AsciiString KEEP_ALIVE = create("Keep-Alive");
    /**
     * The HTTP <a href="https://github.com/WICG/nav-speculation/blob/main/no-vary-search.md">{@code
     * No-Vary-Seearch}</a> header field name.
     */
    public static final AsciiString NO_VARY_SEARCH = create("No-Vary-Search");
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
     * The HTTP <a href="https://github.com/WICG/nav-speculation/blob/main/opt-in.md">{@code
     * Supports-Loading-Mode}</a> header field name. This can be used to specify, for example, <a
     * href="https://developer.chrome.com/docs/privacy-sandbox/fenced-frame/#server-opt-in">fenced
     * frames</a>.
     *
     * @since 32.0.0
     */
    public static final AsciiString SUPPORTS_LOADING_MODE = create("Supports-Loading-Mode");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc6797#section-6.1">{@code
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
     * The HTTP <a
     * href="https://iabtechlab.com/wp-content/uploads/2019/06/VAST_4.2_final_june26.pdf">{@code
     * X-Device-IP}</a> header field name. Header used for VAST requests to provide the IP address of
     * the device on whose behalf the request is being made.
     */
    public static final AsciiString X_DEVICE_IP = create("X-Device-IP");
    /**
     * The HTTP <a
     * href="https://iabtechlab.com/wp-content/uploads/2019/06/VAST_4.2_final_june26.pdf">{@code
     * X-Device-Referer}</a> header field name. Header used for VAST requests to provide the {@link
     * #REFERER} header value that the on-behalf-of client would have used when making a request
     * itself.
     */
    public static final AsciiString X_DEVICE_REFERER = create("X-Device-Referer");
    /**
     * The HTTP <a
     * href="https://iabtechlab.com/wp-content/uploads/2019/06/VAST_4.2_final_june26.pdf">{@code
     * X-Device-Accept-Language}</a> header field name. Header used for VAST requests to provide the
     * {@link #ACCEPT_LANGUAGE} header value that the on-behalf-of client would have used when making
     * a request itself.
     */
    public static final AsciiString X_DEVICE_ACCEPT_LANGUAGE = create("X-Device-Accept-Language");
    /**
     * The HTTP <a
     * href="https://iabtechlab.com/wp-content/uploads/2019/06/VAST_4.2_final_june26.pdf">{@code
     * X-Device-Requested-With}</a> header field name. Header used for VAST requests to provide the
     * {@link #X_REQUESTED_WITH} header value that the on-behalf-of client would have used when making
     * a request itself.
     */
    public static final AsciiString X_DEVICE_REQUESTED_WITH = create("X-Device-Requested-With");
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
     * The HTTP <a href="https://datatracker.ietf.org/doc/rfc7469/">{@code
     * Public-Key-Pins}</a> header field name.
     */
    public static final AsciiString PUBLIC_KEY_PINS = create("Public-Key-Pins");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/rfc7469/">{@code
     * Public-Key-Pins-Report-Only}</a> header field name.
     */
    public static final AsciiString PUBLIC_KEY_PINS_REPORT_ONLY = create("Public-Key-Pins-Report-Only");
    /**
     * The HTTP {@code X-Request-ID} header field name.
     */
    public static final AsciiString X_REQUEST_ID = create("X-Request-ID");
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
     * The HTTP <a
     * href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Link_prefetching_FAQ#as_a_server_admin_can_i_distinguish_prefetch_requests_from_normal_requests">{@code
     * Purpose}</a> header field name.
     */
    public static final AsciiString PURPOSE = create("Purpose");
    /**
     * The HTTP <a
     * href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Link_prefetching_FAQ#as_a_server_admin_can_i_distinguish_prefetch_requests_from_normal_requests">{@code
     * X-Purpose}</a> header field name.
     */
    public static final AsciiString X_PURPOSE = create("X-Purpose");
    /**
     * The HTTP <a
     * href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Link_prefetching_FAQ#as_a_server_admin_can_i_distinguish_prefetch_requests_from_normal_requests">{@code
     * X-Moz}</a> header field name.
     */
    public static final AsciiString X_MOZ = create("X-Moz");
    /**
     * The HTTP <a
     * href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Device-Memory">{@code
     * Device-Memory}</a> header field name.
     */
    public static final AsciiString DEVICE_MEMORY = create("Device-Memory");
    /**
     * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Downlink">{@code
     * Downlink}</a> header field name.
     */
    public static final AsciiString DOWNLINK = create("Downlink");
    /**
     * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ECT">{@code
     * ECT}</a> header field name.
     */
    public static final AsciiString ECT = create("ECT");
    /**
     * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/RTT">{@code
     * RTT}</a> header field name.
     */
    public static final AsciiString RTT = create("RTT");
    /**
     * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Save-Data">{@code
     * Save-Data}</a> header field name.
     */
    public static final AsciiString SAVE_DATA = create("Save-Data");
    /**
     * The HTTP <a
     * href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Viewport-Width">{@code
     * Viewport-Width}</a> header field name.
     */
    public static final AsciiString VIEWPORT_WIDTH = create("Viewport-Width");
    /**
     * The HTTP <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Width">{@code
     * Width}</a> header field name.
     */
    public static final AsciiString WIDTH = create("Width");
    /**
     * The HTTP <a href="https://www.w3.org/TR/permissions-policy-1/">{@code Permissions-Policy}</a>
     * header field name.
     */
    public static final AsciiString PERMISSIONS_POLICY = create("Permissions-Policy");
    /**
     * The HTTP <a
     * href="https://datatracker.ietf.org/doc/html/rfc8942">{@code
     * Accept-CH}</a> header field name.
     */
    public static final AsciiString ACCEPT_CH = create("Accept-CH");
    /**
     * The HTTP <a
     * href="https://datatracker.ietf.org/doc/html/draft-davidben-http-client-hint-reliability-03.txt#section-3">{@code
     * Critical-CH}</a> header field name.
     */
    public static final AsciiString CRITICAL_CH = create("Critical-CH");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua">{@code Sec-CH-UA}</a>
     * header field name.
     */
    public static final AsciiString SEC_CH_UA = create("Sec-CH-UA");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-arch">{@code
     * Sec-CH-UA-Arch}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_ARCH = create("Sec-CH-UA-Arch");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-model">{@code
     * Sec-CH-UA-Model}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_MODEL = create("Sec-CH-UA-Model");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-platform">{@code
     * Sec-CH-UA-Platform}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_PLATFORM = create("Sec-CH-UA-Platform");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-platform-version">{@code
     * Sec-CH-UA-Platform-Version}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_PLATFORM_VERSION = create("Sec-CH-UA-Platform-Version");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-full-version">{@code
     * Sec-CH-UA-Full-Version}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_FULL_VERSION = create("Sec-CH-UA-Full-Version");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-full-version-list">{@code
     * Sec-CH-UA-Full-Version}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_FULL_VERSION_LIST = create("Sec-CH-UA-Full-Version-List");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-mobile">{@code
     * Sec-CH-UA-Mobile}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_MOBILE = create("Sec-CH-UA-Mobile");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-wow64">{@code
     * Sec-CH-UA-WoW64}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_WOW64 = create("Sec-CH-UA-WoW64");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-bitness">{@code
     * Sec-CH-UA-Bitness}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_BITNESS = create("Sec-CH-UA-Bitness");
    /**
     * The HTTP <a href="https://wicg.github.io/ua-client-hints/#sec-ch-ua-form-factor">{@code
     * Sec-CH-UA-Form-Factor}</a> header field name.
     */
    public static final AsciiString SEC_CH_UA_FORM_FACTOR = create("Sec-CH-UA-Form-Factor");
    /**
     * The HTTP <a
     * href="https://wicg.github.io/responsive-image-client-hints/#sec-ch-viewport-width">{@code
     * Sec-CH-Viewport-Width}</a> header field name.
     */
    public static final AsciiString SEC_CH_VIEWPORT_WIDTH = create("Sec-CH-Viewport-Width");
    /**
     * The HTTP <a
     * href="https://wicg.github.io/responsive-image-client-hints/#sec-ch-viewport-height">{@code
     * Sec-CH-Viewport-Height}</a> header field name.
     */
    public static final AsciiString SEC_CH_VIEWPORT_HEIGHT = create("Sec-CH-Viewport-Height");
    /**
     * The HTTP <a href="https://wicg.github.io/responsive-image-client-hints/#sec-ch-dpr">{@code
     * Sec-CH-DPR}</a> header field name.
     */
    public static final AsciiString SEC_CH_DPR = create("Sec-CH-DPR");
    /**
     * The HTTP <a href="https://w3c.github.io/webappsec-fetch-metadata/">{@code Sec-Fetch-Dest}</a>
     * header field name.
     */
    public static final AsciiString SEC_FETCH_DEST = create("Sec-Fetch-Dest");
    /**
     * The HTTP <a href="https://w3c.github.io/webappsec-fetch-metadata/">{@code Sec-Fetch-Mode}</a>
     * header field name.
     */
    public static final AsciiString SEC_FETCH_MODE = create("Sec-Fetch-Mode");
    /**
     * The HTTP <a href="https://w3c.github.io/webappsec-fetch-metadata/">{@code Sec-Fetch-Site}</a>
     * header field name.
     */
    public static final AsciiString SEC_FETCH_SITE = create("Sec-Fetch-Site");
    /**
     * The HTTP <a href="https://w3c.github.io/webappsec-fetch-metadata/">{@code Sec-Fetch-User}</a>
     * header field name.
     */
    public static final AsciiString SEC_FETCH_USER = create("Sec-Fetch-User");
    /**
     * The HTTP <a href="https://w3c.github.io/webappsec-fetch-metadata/">{@code Sec-Metadata}</a>
     * header field name.
     */
    public static final AsciiString SEC_METADATA = create("Sec-Metadata");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/rfc8473/">{@code
     * Sec-Token-Binding}</a> header field name.
     */
    public static final AsciiString SEC_TOKEN_BINDING = create("Sec-Token-Binding");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/draft-ietf-tokbind-ttrp">{@code
     * Sec-Provided-Token-Binding-ID}</a> header field name.
     */
    public static final AsciiString SEC_PROVIDED_TOKEN_BINDING_ID = create("Sec-Provided-Token-Binding-ID");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/draft-ietf-tokbind-ttrp">{@code
     * Sec-Referred-Token-Binding-ID}</a> header field name.
     */
    public static final AsciiString SEC_REFERRED_TOKEN_BINDING_ID = create("Sec-Referred-Token-Binding-ID");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc6455">{@code Sec-WebSocket-Accept}</a> header
     * field name.
     */
    public static final AsciiString SEC_WEBSOCKET_ACCEPT = create("Sec-WebSocket-Accept");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc6455">{@code Sec-WebSocket-Extensions}</a>
     * header field name.
     */
    public static final AsciiString SEC_WEBSOCKET_EXTENSIONS = create("Sec-WebSocket-Extensions");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc6455">{@code Sec-WebSocket-Key}</a> header
     * field name.
     */
    public static final AsciiString SEC_WEBSOCKET_KEY = create("Sec-WebSocket-Key");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc6455">{@code Sec-WebSocket-Protocol}</a>
     * header field name.
     */
    public static final AsciiString SEC_WEBSOCKET_PROTOCOL = create("Sec-WebSocket-Protocol");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc6455">{@code Sec-WebSocket-Version}</a> header
     * field name.
     */
    public static final AsciiString SEC_WEBSOCKET_VERSION = create("Sec-WebSocket-Version");
    /**
     * The HTTP <a href="https://patcg-individual-drafts.github.io/topics/">{@code
     * Sec-Browsing-Topics}</a> header field name.
     */
    public static final AsciiString SEC_BROWSING_TOPICS = create("Sec-Browsing-Topics");
    /**
     * The HTTP <a href="https://patcg-individual-drafts.github.io/topics/">{@code
     * Observe-Browsing-Topics}</a> header field name.
     */
    public static final AsciiString OBSERVE_BROWSING_TOPICS = create("Observe-Browsing-Topics");
    /**
     * The HTTP <a href="https://datatracker.ietf.org/doc/html/rfc8586">{@code CDN-Loop}</a> header field name.
     */
    public static final AsciiString CDN_LOOP = create("CDN-Loop");

    /**
     * The HTTP <a
     * href="https://wicg.github.io/turtledove/#handling-direct-from-seller-signals">{@code
     * Sec-Ad-Auction-Fetch}</a> header field name.
     */
    public static final AsciiString SEC_AD_AUCTION_FETCH = create("Sec-Ad-Auction-Fetch");

    /**
     * The HTTP <a
     * href="https://wicg.github.io/turtledove/#handling-direct-from-seller-signals">{@code
     * Ad-Auction-Signals}</a> header field name.
     */
    public static final AsciiString AD_AUCTION_SIGNALS = create("Ad-Auction-Signals");

    private static final Map<CharSequence, AsciiString> map;
    private static final Map<AsciiString, String> inverseMap;

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
        inverseMap = inverseMapBuilder.build();
        // inverseMapBuilder is used only when building inverseMap.
        inverseMapBuilder = null;
    }

    private static AsciiString create(String name) {
        final AsciiString cached = AsciiString.cached(Ascii.toLowerCase(name));
        inverseMapBuilder.put(cached, name);
        return cached;
    }

    /**
     * Lower-cases and converts the specified header name into an {@link AsciiString}. If {@code "name"} is
     * a known header name, this method will return a pre-instantiated {@link AsciiString} to reduce
     * the allocation rate of {@link AsciiString}.
     *
     * @throws IllegalArgumentException if the specified {@code name} is not a valid header name.
     */
    public static AsciiString of(CharSequence name) {
        if (name instanceof AsciiString) {
            return of((AsciiString) name);
        }

        final String lowerCased = Ascii.toLowerCase(requireNonNull(name, "name"));
        final AsciiString cached = map.get(lowerCased);
        if (cached != null) {
            return cached;
        }

        return validate(AsciiString.cached(lowerCased));
    }

    /**
     * Lower-cases and converts the specified header name into an {@link AsciiString}. If {@code "name"} is
     * a known header name, this method will return a pre-instantiated {@link AsciiString} to reduce
     * the allocation rate of {@link AsciiString}.
     *
     * @throws IllegalArgumentException if the specified {@code name} is not a valid header name.
     */
    public static AsciiString of(AsciiString name) {
        final AsciiString lowerCased = name.toLowerCase();
        final AsciiString cached = map.get(lowerCased);
        if (cached != null) {
            return cached;
        }

        return validate(lowerCased);
    }

    /**
     * Returned the raw header name used when creating the specified {@link AsciiString} with
     * {@link #create(String)}.
     */
    static String rawHeaderName(AsciiString name) {
        final String headerName = inverseMap.get(name);
        if (headerName != null) {
            return headerName;
        } else {
            return name.toString();
        }
    }

    private static AsciiString validate(AsciiString name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("malformed header name: <EMPTY>");
        }

        if (!Flags.validateHeaders()) {
            return name;
        }

        final int lastIndex;
        try {
            lastIndex = name.forEachByte(value -> {
                if (value > LAST_PROHIBITED_NAME_CHAR) {
                    // Definitely valid.
                    return true;
                }

                return !PROHIBITED_NAME_CHARS.get(value);
            });
        } catch (Exception e) {
            throw new Error(e);
        }

        if (lastIndex >= 0) {
            throw new IllegalArgumentException(malformedHeaderNameMessage(name));
        }

        return name;
    }

    private static String malformedHeaderNameMessage(AsciiString name) {
        final StringBuilder buf = new StringBuilder(IntMath.saturatedAdd(name.length(), 64));
        buf.append("malformed header name: ");

        final int nameLength = name.length();
        for (int i = 0; i < nameLength; i++) {
            final char ch = name.charAt(i);
            if (PROHIBITED_NAME_CHARS.get(ch)) {
                buf.append(PROHIBITED_NAME_CHAR_NAMES[ch]);
            } else {
                buf.append(ch);
            }
        }

        return buf.toString();
    }

    private HttpHeaderNames() {}
}
