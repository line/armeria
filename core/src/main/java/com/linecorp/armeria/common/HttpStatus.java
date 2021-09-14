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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.StringUtil;

/**
 * HTTP response code and its description.
 */
public final class HttpStatus implements Comparable<HttpStatus> {

    private static final HttpStatus[] map = new HttpStatus[1000];

    /**
     * 100 Continue.
     */
    public static final HttpStatus CONTINUE = newConstant(100, "Continue");

    /**
     * 101 Switching Protocols.
     */
    public static final HttpStatus SWITCHING_PROTOCOLS = newConstant(101, "Switching Protocols");

    /**
     * 102 Processing (WebDAV, RFC2518).
     */
    public static final HttpStatus PROCESSING = newConstant(102, "Processing");

    /**
     * 200 OK.
     */
    public static final HttpStatus OK = newConstant(200, "OK");

    /**
     * 201 Created.
     */
    public static final HttpStatus CREATED = newConstant(201, "Created");

    /**
     * 202 Accepted.
     */
    public static final HttpStatus ACCEPTED = newConstant(202, "Accepted");

    /**
     * 203 Non-Authoritative Information (since HTTP/1.1).
     */
    public static final HttpStatus NON_AUTHORITATIVE_INFORMATION =
            newConstant(203, "Non-Authoritative Information");

    /**
     * 204 No Content.
     */
    public static final HttpStatus NO_CONTENT = newConstant(204, "No Content");

    /**
     * 205 Reset Content.
     */
    public static final HttpStatus RESET_CONTENT = newConstant(205, "Reset Content");

    /**
     * 206 Partial Content.
     */
    public static final HttpStatus PARTIAL_CONTENT = newConstant(206, "Partial Content");

    /**
     * 207 Multi-Status (WebDAV, RFC2518).
     */
    public static final HttpStatus MULTI_STATUS = newConstant(207, "Multi-Status");

    /**
     * 300 Multiple Choices.
     */
    public static final HttpStatus MULTIPLE_CHOICES = newConstant(300, "Multiple Choices");

    /**
     * 301 Moved Permanently.
     */
    public static final HttpStatus MOVED_PERMANENTLY = newConstant(301, "Moved Permanently");

    /**
     * 302 Found.
     */
    public static final HttpStatus FOUND = newConstant(302, "Found");

    /**
     * 303 See Other (since HTTP/1.1).
     */
    public static final HttpStatus SEE_OTHER = newConstant(303, "See Other");

    /**
     * 304 Not Modified.
     */
    public static final HttpStatus NOT_MODIFIED = newConstant(304, "Not Modified");

    /**
     * 305 Use Proxy (since HTTP/1.1).
     */
    public static final HttpStatus USE_PROXY = newConstant(305, "Use Proxy");

    /**
     * 307 Temporary Redirect (since HTTP/1.1).
     */
    public static final HttpStatus TEMPORARY_REDIRECT = newConstant(307, "Temporary Redirect");

    /**
     * 400 Bad Request.
     */
    public static final HttpStatus BAD_REQUEST = newConstant(400, "Bad Request");

    /**
     * 401 Unauthorized.
     */
    public static final HttpStatus UNAUTHORIZED = newConstant(401, "Unauthorized");

    /**
     * 402 Payment Required.
     */
    public static final HttpStatus PAYMENT_REQUIRED = newConstant(402, "Payment Required");

    /**
     * 403 Forbidden.
     */
    public static final HttpStatus FORBIDDEN = newConstant(403, "Forbidden");

    /**
     * 404 Not Found.
     */
    public static final HttpStatus NOT_FOUND = newConstant(404, "Not Found");

    /**
     * 405 Method Not Allowed.
     */
    public static final HttpStatus METHOD_NOT_ALLOWED = newConstant(405, "Method Not Allowed");

    /**
     * 406 Not Acceptable.
     */
    public static final HttpStatus NOT_ACCEPTABLE = newConstant(406, "Not Acceptable");

    /**
     * 407 Proxy Authentication Required.
     */
    public static final HttpStatus PROXY_AUTHENTICATION_REQUIRED =
            newConstant(407, "Proxy Authentication Required");

    /**
     * 408 Request Timeout.
     */
    public static final HttpStatus REQUEST_TIMEOUT = newConstant(408, "Request Timeout");

    /**
     * 409 Conflict.
     */
    public static final HttpStatus CONFLICT = newConstant(409, "Conflict");

    /**
     * 410 Gone.
     */
    public static final HttpStatus GONE = newConstant(410, "Gone");

    /**
     * 411 Length Required.
     */
    public static final HttpStatus LENGTH_REQUIRED = newConstant(411, "Length Required");

    /**
     * 412 Precondition Failed.
     */
    public static final HttpStatus PRECONDITION_FAILED = newConstant(412, "Precondition Failed");

    /**
     * 413 Request Entity Too Large.
     */
    public static final HttpStatus REQUEST_ENTITY_TOO_LARGE =
            newConstant(413, "Request Entity Too Large");

    /**
     * 414 Request-URI Too Long.
     */
    public static final HttpStatus REQUEST_URI_TOO_LONG = newConstant(414, "Request-URI Too Long");

    /**
     * 415 Unsupported Media Type.
     */
    public static final HttpStatus UNSUPPORTED_MEDIA_TYPE = newConstant(415, "Unsupported Media Type");

    /**
     * 416 Requested Range Not Satisfiable.
     */
    public static final HttpStatus REQUESTED_RANGE_NOT_SATISFIABLE =
            newConstant(416, "Requested Range Not Satisfiable");

    /**
     * 417 Expectation Failed.
     */
    public static final HttpStatus EXPECTATION_FAILED = newConstant(417, "Expectation Failed");

    /**
     * 421 Misdirected Request.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.1.2">421 (Misdirected Request)
     *      Status Code</a>
     */
    public static final HttpStatus MISDIRECTED_REQUEST = newConstant(421, "Misdirected Request");

    /**
     * 422 Unprocessable Entity (WebDAV, RFC4918).
     */
    public static final HttpStatus UNPROCESSABLE_ENTITY = newConstant(422, "Unprocessable Entity");

    /**
     * 423 Locked (WebDAV, RFC4918).
     */
    public static final HttpStatus LOCKED = newConstant(423, "Locked");

    /**
     * 424 Failed Dependency (WebDAV, RFC4918).
     */
    public static final HttpStatus FAILED_DEPENDENCY = newConstant(424, "Failed Dependency");

    /**
     * 425 Unordered Collection (WebDAV, RFC3648).
     */
    public static final HttpStatus UNORDERED_COLLECTION = newConstant(425, "Unordered Collection");

    /**
     * 426 Upgrade Required (RFC2817).
     */
    public static final HttpStatus UPGRADE_REQUIRED = newConstant(426, "Upgrade Required");

    /**
     * 428 Precondition Required (RFC6585).
     */
    public static final HttpStatus PRECONDITION_REQUIRED = newConstant(428, "Precondition Required");

    /**
     * 429 Too Many Requests (RFC6585).
     */
    public static final HttpStatus TOO_MANY_REQUESTS = newConstant(429, "Too Many Requests");

    /**
     * 431 Request Header Fields Too Large (RFC6585).
     */
    public static final HttpStatus REQUEST_HEADER_FIELDS_TOO_LARGE =
            newConstant(431, "Request Header Fields Too Large");

    /**
     * 499 Client Closed Request.
     *
     * @see <a href="https://httpstatuses.com/499">499 CLIENT CLOSED REQUEST</a>
     */
    public static final HttpStatus CLIENT_CLOSED_REQUEST =
            newConstant(499, "Client Closed Request");

    /**
     * 500 Internal Server Error.
     */
    public static final HttpStatus INTERNAL_SERVER_ERROR = newConstant(500, "Internal Server Error");

    /**
     * 501 Not Implemented.
     */
    public static final HttpStatus NOT_IMPLEMENTED = newConstant(501, "Not Implemented");

    /**
     * 502 Bad Gateway.
     */
    public static final HttpStatus BAD_GATEWAY = newConstant(502, "Bad Gateway");

    /**
     * 503 Service Unavailable.
     */
    public static final HttpStatus SERVICE_UNAVAILABLE = newConstant(503, "Service Unavailable");

    /**
     * 504 Gateway Timeout.
     */
    public static final HttpStatus GATEWAY_TIMEOUT = newConstant(504, "Gateway Timeout");

    /**
     * 505 HTTP Version Not Supported.
     */
    public static final HttpStatus HTTP_VERSION_NOT_SUPPORTED =
            newConstant(505, "HTTP Version Not Supported");

    /**
     * 506 Variant Also Negotiates (RFC2295).
     */
    public static final HttpStatus VARIANT_ALSO_NEGOTIATES = newConstant(506, "Variant Also Negotiates");

    /**
     * 507 Insufficient Storage (WebDAV, RFC4918).
     */
    public static final HttpStatus INSUFFICIENT_STORAGE = newConstant(507, "Insufficient Storage");

    /**
     * 510 Not Extended (RFC2774).
     */
    public static final HttpStatus NOT_EXTENDED = newConstant(510, "Not Extended");

    /**
     * 511 Network Authentication Required (RFC6585).
     */
    public static final HttpStatus NETWORK_AUTHENTICATION_REQUIRED =
            newConstant(511, "Network Authentication Required");

    /**
     * A special status code '0' which represents that the response status is unknown.
     */
    public static final HttpStatus UNKNOWN = newConstant(0, "Unknown reason");

    static {
        for (int i = 0; i < 1000; i++) {
            if (map[i] == null) {
                map[i] = new HttpStatus(i);
            }
        }
    }

    private static HttpStatus newConstant(int statusCode, String reasonPhrase) {
        final HttpStatus status = new HttpStatus(statusCode, reasonPhrase);
        map[statusCode] = status;
        return status;
    }

    /**
     * Returns the {@link HttpStatus} represented by the specified status code.
     */
    public static HttpStatus valueOf(int statusCode) {
        if (statusCode < 0 || statusCode >= 1000) {
            return new HttpStatus(statusCode);
        } else {
            return map[statusCode];
        }
    }

    /**
     * Returns the {@link HttpStatus} represented by the specified status text.
     *
     * @return the parsed {@link HttpStatus}, or {@link #UNKNOWN} if failed to parse.
     *
     * @see #isContentAlwaysEmpty()
     */
    public static HttpStatus valueOf(String statusText) {
        requireNonNull(statusText, "statusText");
        final int spaceIdx = statusText.indexOf(' ');
        final int statusCode;
        try {
            if (spaceIdx < 0) {
                statusCode = Integer.parseInt(statusText);
            } else if (spaceIdx > 0) {
                statusCode = Integer.parseInt(statusText.substring(0, spaceIdx));
            } else {
                return UNKNOWN;
            }
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }

        return valueOf(statusCode);
    }

    /**
     * Returns {@code true} if the content of the response for the specified status code is expected to
     * be always empty (204, 205 and 304 responses.)
     */
    @SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
    public static boolean isContentAlwaysEmpty(int statusCode) {
        switch (statusCode) {
            case /* NO_CONTENT */ 204:
            case /* RESET_CONTENT */ 205:
            case /* NOT_MODIFIED */ 304:
                return true;
        }
        return false;
    }

    private final int code;
    private final String codeAsText;
    private final HttpStatusClass codeClass;
    private final String reasonPhrase;
    private final HttpData httpData;
    private final String strVal;

    /**
     * Creates a new instance with the specified status code and the auto-generated default reason phrase.
     */
    private HttpStatus(int statusCode) {
        this(statusCode, HttpStatusClass.valueOf(statusCode).defaultReasonPhrase() + " (" + statusCode + ')');
    }

    /**
     * Creates a new instance with the specified status code and its reason phrase.
     */
    public HttpStatus(int statusCode, @Nullable String reasonPhrase) {
        if (statusCode < 0) {
            throw new IllegalArgumentException(
                    "statusCode: " + statusCode + " (expected: 0+)");
        }

        if (reasonPhrase == null) {
            throw new NullPointerException("reasonPhrase");
        }

        for (int i = 0; i < reasonPhrase.length(); i++) {
            final char c = reasonPhrase.charAt(i);
            // Check prohibited characters.
            switch (c) {
                case '\n':
                case '\r':
                    throw new IllegalArgumentException(
                            "reasonPhrase contains one of the following prohibited characters: " +
                            "\\r\\n: " + reasonPhrase);
            }
        }

        code = statusCode;
        codeAsText = StringUtil.toString(statusCode);
        codeClass = HttpStatusClass.valueOf(statusCode);
        this.reasonPhrase = reasonPhrase;

        strVal = new StringBuilder(reasonPhrase.length() + 5).append(statusCode)
                                                             .append(' ')
                                                             .append(reasonPhrase)
                                                             .toString();
        httpData = HttpData.ofUtf8(strVal);
    }

    /**
     * Returns the code of this {@link HttpStatus}.
     */
    public int code() {
        return code;
    }

    /**
     * Returns the status code as {@link String}.
     */
    public String codeAsText() {
        return codeAsText;
    }

    /**
     * Returns the reason phrase of this {@link HttpStatus}.
     */
    public String reasonPhrase() {
        return reasonPhrase;
    }

    /**
     * Returns the class of this {@link HttpStatus}.
     */
    public HttpStatusClass codeClass() {
        return codeClass;
    }

    /**
     * Returns the {@link HttpData} whose content is {@code "<code> <reasonPhrase>"} encoded in UTF-8.
     * Do not modify the content of the returned {@link HttpData}; it will be reused.
     */
    public HttpData toHttpData() {
        return httpData;
    }

    /**
     * Returns {@code true} if the content of the response for this {@link HttpStatus} is expected to
     * be always empty (204, 205 and 304 responses.)
     *
     * @see #isContentAlwaysEmpty(int)
     */
    @SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
    public boolean isContentAlwaysEmpty() {
        return isContentAlwaysEmpty(code);
    }

    /**
     * Returns whether the {@link HttpStatus} is an information, with a status code of 1XX.
     */
    public boolean isInformational() {
        return codeClass == HttpStatusClass.INFORMATIONAL;
    }

    /**
     * Returns whether the {@link HttpStatus} is a success, with a status code of 2XX.
     */
    public boolean isSuccess() {
        return codeClass == HttpStatusClass.SUCCESS;
    }

    /**
     * Returns whether the {@link HttpStatus} is a redirection, with a status code of 3XX.
     */
    public boolean isRedirection() {
        return codeClass == HttpStatusClass.REDIRECTION;
    }

    /**
     * Returns whether the {@link HttpStatus} is a client error, with a status code of 4XX.
     */
    public boolean isClientError() {
        return codeClass == HttpStatusClass.CLIENT_ERROR;
    }

    /**
     * Returns whether the {@link HttpStatus} is a server error, with a status code of 5XX.
     */
    public boolean isServerError() {
        return codeClass == HttpStatusClass.SERVER_ERROR;
    }

    /**
     * Returns whether the {@link HttpStatus} is an error.
     */
    public boolean isError() {
        return isClientError() || isServerError();
    }

    @Override
    public int hashCode() {
        return code();
    }

    /**
     * Returns whether the specified object is "equal to" this status.
     *
     * <p>Equality of {@link HttpStatus} only depends on {@link #code()}. The reason phrase is not considered
     * for equality.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof HttpStatus)) {
            return false;
        }

        return code() == ((HttpStatus) o).code();
    }

    /**
     * Compares this status to the specified status.
     *
     * <p>Equality of {@link HttpStatus} only depends on {@link #code()}. The reason phrase is not considered
     * for equality.
     */
    @Override
    public int compareTo(HttpStatus o) {
        return Integer.compare(code(), o.code());
    }

    @Override
    public String toString() {
        return strVal;
    }
}
