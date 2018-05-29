/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

import io.netty.channel.EventLoop;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

public class DefaultClientRequestContextTest {

    @Test
    public void deriveContext() {
        final DefaultClientRequestContext originalCtx = new DefaultClientRequestContext(
                mock(EventLoop.class), NoopMeterRegistry.get(), SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/foo", null, null,
                ClientOptions.DEFAULT, mock(Request.class));

        setAdditionalHeaders(originalCtx);

        final AttributeKey<String> foo = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "foo");
        originalCtx.attr(foo).set("foo");

        final Request newRequest = mock(Request.class);
        final ClientRequestContext derivedCtx = originalCtx.newDerivedContext(newRequest);
        assertThat(derivedCtx.endpoint()).isSameAs(originalCtx.endpoint());
        assertThat(derivedCtx.sessionProtocol()).isSameAs(originalCtx.sessionProtocol());
        assertThat(derivedCtx.method()).isSameAs(originalCtx.method());
        assertThat(derivedCtx.options()).isSameAs(originalCtx.options());
        assertThat(derivedCtx.<Request>request()).isSameAs(newRequest);

        assertThat(derivedCtx.path()).isEqualTo(originalCtx.path());
        assertThat(derivedCtx.maxResponseLength()).isEqualTo(originalCtx.maxResponseLength());
        assertThat(derivedCtx.responseTimeoutMillis()).isEqualTo(originalCtx.responseTimeoutMillis());
        assertThat(derivedCtx.writeTimeoutMillis()).isEqualTo(originalCtx.writeTimeoutMillis());
        assertThat(derivedCtx.additionalRequestHeaders().get(AsciiString.of("my-header#1"))).isNull();
        assertThat(derivedCtx.additionalRequestHeaders().get(AsciiString.of("my-header#2")))
                .isEqualTo("value#2");
        assertThat(derivedCtx.additionalRequestHeaders().get(AsciiString.of("my-header#3")))
                .isEqualTo("value#3");
        assertThat(derivedCtx.additionalRequestHeaders().get(AsciiString.of("my-header#4")))
                .isEqualTo("value#4");
        // the attribute is derived as well
        assertThat(derivedCtx.attr(foo).get()).isEqualTo("foo");

        // log is different
        assertThat(derivedCtx.log()).isNotSameAs(originalCtx.log());

        final AttributeKey<String> bar = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "bar");
        originalCtx.attr(bar).set("bar");

        // the Attribute added to the original context after creation is not propagated to the derived context
        assertThat(derivedCtx.attr(bar).get()).isEqualTo(null);
    }

    private static void setAdditionalHeaders(ClientRequestContext originalCtx) {
        final DefaultHttpHeaders headers1 = new DefaultHttpHeaders();
        headers1.set(AsciiString.of("my-header#1"), "value#1");
        originalCtx.setAdditionalRequestHeaders(headers1);
        originalCtx.setAdditionalRequestHeader(AsciiString.of("my-header#2"), "value#2");

        final DefaultHttpHeaders headers2 = new DefaultHttpHeaders();
        headers2.set(AsciiString.of("my-header#3"), "value#3");
        originalCtx.addAdditionalRequestHeaders(headers2);
        originalCtx.addAdditionalRequestHeader(AsciiString.of("my-header#4"), "value#4");
        // Remove the first one.
        originalCtx.removeAdditionalRequestHeader(AsciiString.of("my-header#1"));
    }
}
