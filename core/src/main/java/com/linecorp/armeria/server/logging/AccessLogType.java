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

import static com.linecorp.armeria.server.logging.AccessLogType.VariableRequirement.NO;
import static com.linecorp.armeria.server.logging.AccessLogType.VariableRequirement.OPTIONAL;
import static com.linecorp.armeria.server.logging.AccessLogType.VariableRequirement.YES;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;

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
     * {@code "%A"} - the local IP address.
     */
    LOCAL_IP_ADDRESS('A', false, NO),
    /**
     * {@code "%a"} - the IP address of the client who initiated a request. Use {@code "%{c}a"} format string
     * to get the remote IP address where the channel is connected to, which may yield a different value
     * when there is an intermediary proxy server.
     */
    REMOTE_IP_ADDRESS('a', false, OPTIONAL),
    /**
     * {@code "%h"} - the remote hostname or IP address if DNS hostname lookup is not available.
     */
    REMOTE_HOST('h', false, NO),
    /**
     * {@code "%l"} - the remote logname of the user.
     */
    RFC931('l', false, NO),
    /**
     * {@code "%u"} - the name of the authenticated remote user.
     */
    AUTHENTICATED_USER('u', false, NO),
    /**
     * {@code "%t"} - the date, time and time zone that the request was received.
     */
    REQUEST_TIMESTAMP('t', false, OPTIONAL),
    /**
     * {@code "%r"} - the request line from the client.
     */
    REQUEST_LINE('r', true, NO),
    /**
     * {@code "%s"} - the HTTP status code returned to the client.
     */
    RESPONSE_STATUS_CODE('s', false, NO),
    /**
     * {@code "%b"} - the size of the object returned to the client, measured in bytes.
     */
    RESPONSE_LENGTH('b', true, NO),
    /**
     * {@code "%{HEADER_NAME}i"} - the name of HTTP request header.
     */
    REQUEST_HEADER('i', true, YES),
    /**
     * {@code "%{HEADER_NAME}o"} - the name of HTTP response header.
     */
    RESPONSE_HEADER('o', true, YES),
    /**
     * {@code "%{ATTRIBUTE_NAME}j"} - the attribute name of the {@link AttributeMap} of the
     * {@link RequestContext}.
     */
    ATTRIBUTE('j', true, YES),
    /**
     * {@code "%{REQUEST_LOG_NAME}L"} - the name of the attributes in the {@link RequestLog}.
     */
    REQUEST_LOG('L', true, YES),
    /**
     * A plain text which would be written to access log message.
     */
    TEXT('%', false, NO),
    /**
     * {@code "%I"} - the {@link RequestId}. Use {@code "%{short}I"} to get the short form.
     */
    REQUEST_ID('I', false, OPTIONAL);

    private static final Map<Character, AccessLogType> tokenToEnum;

    static {
        final ImmutableMap.Builder<Character, AccessLogType> builder = ImmutableMap.builder();
        for (AccessLogType k : AccessLogType.values()) {
            builder.put(k.token, k);
        }
        tokenToEnum = builder.build();
    }

    @Nullable
    static AccessLogType find(char token) {
        return tokenToEnum.get(token);
    }

    enum VariableRequirement {
        YES, NO, OPTIONAL
    }

    private final char token;
    private final boolean isConditionAvailable;
    private final VariableRequirement variableRequirement;

    AccessLogType(char token, boolean isConditionAvailable, VariableRequirement variableRequirement) {
        this.token = token;
        this.isConditionAvailable = isConditionAvailable;
        this.variableRequirement = variableRequirement;
    }

    char token() {
        return token;
    }

    boolean isConditionAvailable() {
        return isConditionAvailable;
    }

    VariableRequirement variableRequirement() {
        return variableRequirement;
    }
}
