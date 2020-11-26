/*
 * Copyright 2020 LINE Corporation
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
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.spring.web.reactive;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Common base class for {@link ServerHttpRequest} implementations.
 *
 * @author Rossen Stoyanchev
 * @since 5.0
 */
abstract class AbstractServerHttpRequest implements ServerHttpRequest {

    // Forked from https://github.com/spring-projects/spring-framework/blob/80701082cd872a2526823dc26bc7f9271e52bd48/spring-web/src/main/java/org/springframework/http/server/reactive/AbstractServerHttpRequest.java#L41

    // TODO(ikhoon): Remove this class once https://github.com/spring-projects/spring-framework/issues/26151
    //               is resolved.

    private static final Pattern QUERY_PATTERN = Pattern.compile("([^&=]+)(=?)([^&]+)?");

    private final URI uri;

    private final RequestPath path;

    private final HttpHeaders headers;

    @Nullable
    private MultiValueMap<String, String> queryParams;

    @Nullable
    private MultiValueMap<String, HttpCookie> cookies;

    @Nullable
    private SslInfo sslInfo;

    @Nullable
    private String id;

    @Nullable
    private String logPrefix;

    /**
     * Constructor with the URI and headers for the request.
     * @param uri the URI for the request
     * @param contextPath the context path for the request
     * @param headers the headers for the request
     */
    AbstractServerHttpRequest(URI uri, @Nullable String contextPath, HttpHeaders headers) {
        this.uri = uri;
        path = RequestPath.parse(uri, contextPath);
        this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
    }

    @Override
    public String getId() {
        if (id == null) {
            id = initId();
            if (id == null) {
                id = ObjectUtils.getIdentityHexString(this);
            }
        }
        return id;
    }

    /**
     * Obtain the request id to use, or {@code null} in which case the Object
     * identity of this request instance is used.
     * @since 5.1
     */
    @Nullable
    protected String initId() {
        return null;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public RequestPath getPath() {
        return path;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public MultiValueMap<String, String> getQueryParams() {
        if (queryParams == null) {
            queryParams = CollectionUtils.unmodifiableMultiValueMap(initQueryParams());
        }
        return queryParams;
    }

    /**
     * A method for parsing of the query into name-value pairs. The return
     * value is turned into an immutable map and cached.
     *
     * <p>Note that this method is invoked lazily on first access to
     * {@link #getQueryParams()}. The invocation is not synchronized but the
     * parsing is thread-safe nevertheless.
     */
    protected MultiValueMap<String, String> initQueryParams() {
        final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        final String query = getURI().getRawQuery();
        if (query != null) {
            final Matcher matcher = QUERY_PATTERN.matcher(query);
            while (matcher.find()) {
                final String name = decodeQueryParam(matcher.group(1));
                final String eq = matcher.group(2);
                String value = matcher.group(3);
                value = value != null ? decodeQueryParam(value) : StringUtils.hasLength(eq) ? "" : null;
                queryParams.add(name, value);
            }
        }
        return queryParams;
    }

    @SuppressWarnings("deprecation")
    private String decodeQueryParam(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            // Should never happen but we got a platform default fallback anyway.
            return URLDecoder.decode(value);
        }
    }

    @Override
    public MultiValueMap<String, HttpCookie> getCookies() {
        if (cookies == null) {
            cookies = CollectionUtils.unmodifiableMultiValueMap(initCookies());
        }
        return cookies;
    }

    /**
     * Obtain the cookies from the underlying "native" request and adapt those to
     * an {@link HttpCookie} map. The return value is turned into an immutable
     * map and cached.
     *
     * <p>Note that this method is invoked lazily on access to
     * {@link #getCookies()}. Sub-classes should synchronize cookie
     * initialization if the underlying "native" request does not provide
     * thread-safe access to cookie data.
     */
    protected abstract MultiValueMap<String, HttpCookie> initCookies();

    @Nullable
    @Override
    public SslInfo getSslInfo() {
        if (sslInfo == null) {
            sslInfo = initSslInfo();
        }
        return sslInfo;
    }

    /**
     * Obtain SSL session information from the underlying "native" request.
     * @return the session information, or {@code null} if none available
     * @since 5.0.2
     */
    @Nullable
    protected abstract SslInfo initSslInfo();

    /**
     * Return the underlying server response.
     *
     * <p><strong>Note:</strong> This is exposed mainly for internal framework
     * use such as WebSocket upgrades in the spring-webflux module.
     */
    public abstract <T> T getNativeRequest();
}
