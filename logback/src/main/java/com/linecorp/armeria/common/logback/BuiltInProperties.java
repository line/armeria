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
package com.linecorp.armeria.common.logback;

import static com.linecorp.armeria.common.logback.BuiltInProperty.LOCAL_HOST;
import static com.linecorp.armeria.common.logback.BuiltInProperty.LOCAL_IP;
import static com.linecorp.armeria.common.logback.BuiltInProperty.LOCAL_PORT;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REMOTE_HOST;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REMOTE_IP;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REMOTE_PORT;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_RPC_METHOD;
import static com.linecorp.armeria.common.logback.BuiltInProperty.REQ_RPC_PARAMS;
import static com.linecorp.armeria.common.logback.BuiltInProperty.RES_RPC_RESULT;
import static com.linecorp.armeria.common.logback.BuiltInProperty.TLS_CIPHER;
import static com.linecorp.armeria.common.logback.BuiltInProperty.TLS_PROTO;
import static com.linecorp.armeria.common.logback.BuiltInProperty.TLS_SESSION_ID;

final class BuiltInProperties {

    private static final BuiltInProperty[] allValues = BuiltInProperty.values();

    private static final long MASK_ADDRESSES =
            mask(REMOTE_HOST, REMOTE_IP, REMOTE_PORT, LOCAL_HOST, LOCAL_IP, LOCAL_PORT);
    private static final long MASK_RPC = mask(REQ_RPC_METHOD, REQ_RPC_PARAMS, RES_RPC_RESULT);
    private static final long MASK_SSL = mask(TLS_SESSION_ID, TLS_CIPHER, TLS_PROTO);

    private long elements;

    void add(BuiltInProperty e) {
        elements |= 1L << e.ordinal();
    }

    boolean contains(BuiltInProperty e) {
        return (elements & mask(e)) != 0;
    }

    boolean containsAddresses() {
        return (elements & MASK_ADDRESSES) != 0;
    }

    boolean containsRpc() {
        return (elements & MASK_RPC) != 0;
    }

    boolean containsSsl() {
        return (elements & MASK_SSL) != 0;
    }

    private static long mask(BuiltInProperty e) {
        return 1L << e.ordinal();
    }

    private static long mask(BuiltInProperty... elems) {
        long mask = 0L;
        for (BuiltInProperty e : elems) {
            mask |= mask(e);
        }
        return mask;
    }
}
