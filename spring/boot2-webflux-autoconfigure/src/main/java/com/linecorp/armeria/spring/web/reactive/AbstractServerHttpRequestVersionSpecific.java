/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.spring.web.reactive;

import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.util.MultiValueMap;

/**
 * A version specific {@link AbstractServerHttpRequest} which implements the APIs that only exists in Spring 5.
 */
abstract class AbstractServerHttpRequestVersionSpecific extends AbstractServerHttpRequest {
    protected AbstractServerHttpRequestVersionSpecific(HttpMethod unused, URI uri, String contextPath,
                                                       MultiValueMap<String, String> headers) {
        super(uri, contextPath, headers);
    }
}
