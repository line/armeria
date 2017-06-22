/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.http;

import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.Set;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * @deprecated Use {@link SessionProtocol} instead.
 */
@Deprecated
public final class HttpSessionProtocols {

    /**
     * @deprecated Use {@link SessionProtocol#HTTPS} instead.
     */
    @Deprecated
    public static final SessionProtocol HTTPS = SessionProtocol.HTTPS;

    /**
     * @deprecated Use {@link SessionProtocol#HTTP} instead.
     */
    @Deprecated
    public static final SessionProtocol HTTP = SessionProtocol.HTTP;

    /**
     * @deprecated Use {@link SessionProtocol#H1} instead.
     */
    @Deprecated
    public static final SessionProtocol H1 = SessionProtocol.H1;

    /**
     * @deprecated Use {@link SessionProtocol#H1C} instead.
     */
    @Deprecated
    public static final SessionProtocol H1C = SessionProtocol.H1C;

    /**
     * @deprecated Use {@link SessionProtocol#H2} instead.
     */
    @Deprecated
    public static final SessionProtocol H2 = SessionProtocol.H2;

    /**
     * @deprecated Use {@link SessionProtocol#H2C} instead.
     */
    @Deprecated
    public static final SessionProtocol H2C = SessionProtocol.H2C;

    private static final Set<SessionProtocol> HTTP_PROTOCOLS = EnumSet.allOf(SessionProtocol.class);

    /**
     * @deprecated Use {@link SessionProtocol#values()} instead.
     */
    @Deprecated
    public static Set<SessionProtocol> values() {
        return HTTP_PROTOCOLS;
    }

    /**
     * @deprecated This method will be removed without a replacement, because we do not support other
     *             protocols than HTTP.
     */
    @Deprecated
    public static boolean isHttp(SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");
        return true;
    }

    private HttpSessionProtocols() {}
}
