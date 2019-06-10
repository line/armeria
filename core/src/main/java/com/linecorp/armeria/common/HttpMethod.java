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
/*
 * Copyright 2012 The Netty Project
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

import java.util.EnumSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

/**
 * HTTP request method.
 */
public enum HttpMethod {

    // Forked from Netty 4.1.34 at ff7484864b1785103cbc62845ff3a392c93822b7

    /**
     * The OPTIONS method which represents a request for information about the communication options
     * available on the request/response chain identified by the Request-URI. This method allows
     * the client to determine the options and/or requirements associated with a resource, or the
     * capabilities of a server, without implying a resource action or initiating a resource
     * retrieval.
     */
    OPTIONS,

    /**
     * The GET method which means retrieve whatever information (in the form of an entity) is identified
     * by the Request-URI.  If the Request-URI refers to a data-producing process, it is the
     * produced data which shall be returned as the entity in the response and not the source text
     * of the process, unless that text happens to be the output of the process.
     */
    GET,

    /**
     * The HEAD method which is identical to GET except that the server MUST NOT return a message-body
     * in the response.
     */
    HEAD,

    /**
     * The POST method which is used to request that the origin server accept the entity enclosed in the
     * request as a new subordinate of the resource identified by the Request-URI in the
     * Request-Line.
     */
    POST,

    /**
     * The PUT method which requests that the enclosed entity be stored under the supplied Request-URI.
     */
    PUT,

    /**
     * The PATCH method which requests that a set of changes described in the
     * request entity be applied to the resource identified by the Request-URI.
     */
    PATCH,

    /**
     * The DELETE method which requests that the origin server delete the resource identified by the
     * Request-URI.
     */
    DELETE,

    /**
     * The TRACE method which is used to invoke a remote, application-layer loop-back of the request
     * message.
     */
    TRACE,

    /**
     * The CONNECT method which is used for a proxy that can dynamically switch to being a tunnel.
     */
    CONNECT,

    /**
     * A special method which represents the client sent a method that is none of the constants defined in
     * this enum.
     */
    UNKNOWN;

    private static final Set<HttpMethod> knownMethods;

    static {
        final Set<HttpMethod> allMethods = EnumSet.allOf(HttpMethod.class);
        allMethods.remove(UNKNOWN);
        knownMethods = ImmutableSet.copyOf(allMethods);
    }

    /**
     * Returns whether the specified {@link String} is one of the supported method names.
     *
     * @return {@code true} if supported. {@code false} otherwise.
     */
    public static boolean isSupported(String value) {
        requireNonNull(value, "value");
        switch (value) {
            case "OPTIONS":
            case "GET":
            case "HEAD":
            case "POST":
            case "PUT":
            case "PATCH":
            case "DELETE":
            case "TRACE":
            case "CONNECT":
                return true;
        }

        return false;
    }

    /**
     * Returns all {@link HttpMethod}s except {@link #UNKNOWN}.
     */
    public static Set<HttpMethod> knownMethods() {
        return knownMethods;
    }
}
