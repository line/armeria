package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkNotNull;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.internal.common.ArmeriaHttpHeaders;

import io.netty.handler.codec.http.DefaultHttpMessage;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.internal.ObjectUtil;

public class ArmeriaDefaultHttpRequest extends DefaultHttpMessage implements HttpRequest {
    private static final int HASH_CODE_PRIME = 31;
    private HttpMethod method;
    private final RequestHeadersBuilder builder;
    private final HttpHeaders headers;
    private String uri;

    /**
     * Creates a new instance.
     *
     * @param httpVersion the HTTP version of the request
     * @param method      the HTTP method of the request
     * @param uri         the URI or path of the request
     */
    public ArmeriaDefaultHttpRequest(HttpVersion httpVersion, HttpMethod method, String uri) {
        this(httpVersion, method, uri, true);
    }

    /**
     * Creates a new instance.
     *
     * @param httpVersion       the HTTP version of the request
     * @param method            the HTTP method of the request
     * @param uri               the URI or path of the request
     * @param validateHeaders   validate the header names and values when adding them to the {@link HttpHeaders}
     */
    public ArmeriaDefaultHttpRequest(HttpVersion httpVersion, HttpMethod method, String uri, boolean validateHeaders) {
        super(httpVersion, validateHeaders, false);
        this.method = checkNotNull(method, "method");
        this.uri = checkNotNull(uri, "uri");
        builder = RequestHeaders.builder(
                com.linecorp.armeria.common.HttpMethod.valueOf(method.name()), uri);
        headers = new ArmeriaHttpHeaders(builder);
    }

    /**
     * Creates a new instance.
     *
     * @param httpVersion       the HTTP version of the request
     * @param method            the HTTP method of the request
     * @param uri               the URI or path of the request
     * @param givenHeaders      the Headers for this Request
     */
    public ArmeriaDefaultHttpRequest(HttpVersion httpVersion, HttpMethod method, String uri, HttpHeaders givenHeaders) {
        super(httpVersion);
        this.method = checkNotNull(method, "method");
        this.uri = checkNotNull(uri, "uri");
        builder = RequestHeaders.builder(
                com.linecorp.armeria.common.HttpMethod.valueOf(method.name()), uri);
        headers = new ArmeriaHttpHeaders(builder, givenHeaders);
    }

    @Override
    @Deprecated
    public HttpMethod getMethod() {
        return method();
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    @Deprecated
    public String getUri() {
        return uri();
    }

    @Override
    public String uri() {
        return uri;
    }

    public RequestHeadersBuilder requestHeadersBuilder() {
        return builder;
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        this.method = ObjectUtil.checkNotNull(method, "method");
        return this;
    }

    @Override
    public HttpRequest setUri(String uri) {
        this.uri = ObjectUtil.checkNotNull(uri, "uri");
        return this;
    }

    @Override
    public HttpRequest setProtocolVersion(HttpVersion version) {
        super.setProtocolVersion(version);
        return this;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = HASH_CODE_PRIME * result + method.hashCode();
        result = HASH_CODE_PRIME * result + uri.hashCode();
        result = HASH_CODE_PRIME * result + super.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ArmeriaDefaultHttpRequest)) {
            return false;
        }

        ArmeriaDefaultHttpRequest other = (ArmeriaDefaultHttpRequest) o;

        return method().equals(other.method()) &&
               uri().equalsIgnoreCase(other.uri()) &&
               super.equals(o);
    }

//    @Override
//    public String toString() {
//        return HttpMessageUtil.appendRequest(new StringBuilder(256), this).toString();
//    }
}