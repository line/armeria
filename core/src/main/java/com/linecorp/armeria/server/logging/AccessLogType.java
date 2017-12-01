/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.logging;

import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AttributeMap;

/**
 * Access log component types which are used to specify a log message format. Some components can be
 * omitted with a condition. For example:
 *
 * <table summary="Example of a condition">
 * <tr><th>format</th><th>description</th></tr>
 *
 * <tr><td>{@code %200,304{User-Agent}i}</td>
 * <td>Write {@code User-Agent} header value only if the response code is {@code 200} or {@code 304}.</td></tr>
 *
 * <tr><td>{@code %!200,304{com.example.armeria.Attribute#KEY}j}</td>
 * <td>Write the value of the specified attribute only if the response code is neither {@code 200} nor
 * {@code 304}.</td></tr>
 *
 * </table>
 */
enum AccessLogType {
    /**
     * {@code "%h"} - the remote hostname or IP address if DNS hostname lookup is not available.
     */
    REMOTE_HOST('h', false, false),

    /**
     * {@code "%l"} - the remote logname of the user.
     */
    RFC931('l', false, false),

    /**
     * {@code "%u"} - the name of the authenticated remote user.
     */
    AUTHENTICATED_USER('u', false, false),

    /**
     * {@code "%t"} - the date, time and time zone that the request was received.
     */
    REQUEST_TIMESTAMP('t', false, false),

    /**
     * {@code "%r"} - the request line from the client.
     */
    REQUEST_LINE('r', true, false),

    /**
     * {@code "%s"} - the HTTP status code returned to the client.
     */
    RESPONSE_STATUS_CODE('s', false, false),

    /**
     * {@code "%b"} - the size of the object returned to the client, measured in bytes.
     */
    RESPONSE_LENGTH('b', true, false),

    /**
     * {@code "%{HEADER_NAME}i"} - the name of HTTP request header.
     */
    REQUEST_HEADER('i', true, true),

    /**
     * {@code "%{ATTRIBUTE_NAME}j"} - the attribute name of the {@link AttributeMap} of the
     * {@link RequestContext}.
     */
    ATTRIBUTE('j', true, true),

    /**
     * A plain text which would be written to access log message.
     */
    TEXT('%', false, true);

    private static final Map<Character, AccessLogType> tokenToEnum;

    static {
        ImmutableMap.Builder<Character, AccessLogType> builder = ImmutableMap.builder();
        for (AccessLogType k : AccessLogType.values()) {
            builder.put(k.token, k);
        }
        tokenToEnum = builder.build();
    }

    static Optional<AccessLogType> find(char token) {
        return Optional.ofNullable(tokenToEnum.get(token));
    }

    private final char token;
    private final boolean isConditionAvailable;
    private final boolean isVariableRequired;

    AccessLogType(char token, boolean isConditionAvailable, boolean isVariableRequired) {
        this.token = token;
        this.isConditionAvailable = isConditionAvailable;
        this.isVariableRequired = isVariableRequired;
    }

    public char token() {
        return token;
    }

    public boolean isConditionAvailable() {
        return isConditionAvailable;
    }

    public boolean isVariableRequired() {
        return isVariableRequired;
    }
}
