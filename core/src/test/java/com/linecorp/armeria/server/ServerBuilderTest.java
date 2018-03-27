/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.Test;

public class ServerBuilderTest {

    @Test
    public void portUnificationDisabled() {
        final ServerBuilder sb = new ServerBuilder();
        sb.http(8080);
        assertPortUnificationFailure(() -> sb.https(8080));
        // PROXY should be OK.
        sb.proxyProtocol(8080);

        // Try to add PROXY first.
        final ServerBuilder sb2 = new ServerBuilder();
        sb2.proxyProtocol(8080);
        sb2.https(8080);
        assertPortUnificationFailure(() -> sb2.http(8080));
    }

    @Test
    public void portUnificationEnabled() {
        final ServerBuilder sb = new ServerBuilder();
        sb.usePortUnification();
        sb.http(8080);
        sb.https(8080);
        sb.proxyProtocol(8080);
    }

    private static void assertPortUnificationFailure(ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usePortUnification()");
    }

    @Test
    public void proxyWithoutAnyOtherProtocols() {
        final ServerBuilder sb = new ServerBuilder();
        sb.proxyProtocol(8080);
        assertThatThrownBy(sb::build).isInstanceOf(IllegalStateException.class)
                                     .hasMessageContaining("proxy");
    }
}
