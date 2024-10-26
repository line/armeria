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
package com.linecorp.armeria.common.micrometer.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshot.Scope;
import io.micrometer.context.ContextSnapshotFactory;

class RequestContextThreadLocalAccessorTest {

    @Test
    void should_return_expected_key() {
        // Given
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();
        final Object expectedValue = RequestContext.class;

        // When
        final Object result = reqCtxAccessor.key();

        // Then
        assertThat(result).isEqualTo(expectedValue);
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void should_success_set() {
        // Given
        final ClientRequestContext ctx = newContext();
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();

        // When
        reqCtxAccessor.setValue(ctx);

        // Then
        final RequestContext currentCtx = RequestContext.current();
        assertThat(currentCtx).isEqualTo(ctx);

        RequestContextUtil.pop();
    }

    @Test
    void should_throw_NPE_when_set_null() {
        // Given
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();

        // When + Then
        assertThatThrownBy(() -> reqCtxAccessor.setValue(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_be_null_when_setValue() {
        // Given
        final ClientRequestContext ctx = newContext();
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();
        reqCtxAccessor.setValue(ctx);

        // When
        reqCtxAccessor.setValue();

        // Then
        final RequestContext reqCtx = RequestContext.currentOrNull();
        assertThat(reqCtx).isNull();
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void should_be_restore_original_state_when_restore() {
        // Given
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();
        final ClientRequestContext previousCtx = newContext();
        final ClientRequestContext currentCtx = newContext();
        reqCtxAccessor.setValue(currentCtx);

        // When
        reqCtxAccessor.restore(previousCtx);

        // Then
        final RequestContext reqCtx = RequestContext.currentOrNull();
        assertThat(reqCtx).isNotNull();
        assertThat(reqCtx).isEqualTo(previousCtx);

        RequestContextUtil.pop();
    }

    @Test
    void should_be_null_when_restore() {
        // Given
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();
        final ClientRequestContext currentCtx = newContext();
        reqCtxAccessor.setValue(currentCtx);

        // When
        reqCtxAccessor.restore();

        // Then
        final RequestContext reqCtx = RequestContext.currentOrNull();
        assertThat(reqCtx).isNull();
    }

    @Test
    void requestContext_should_exist_inside_scope_and_not_outside() {
        // Given
        final RequestContextThreadLocalAccessor reqCtxAccessor = new RequestContextThreadLocalAccessor();
        ContextRegistry.getInstance()
                       .registerThreadLocalAccessor(reqCtxAccessor);
        final ClientRequestContext currentCtx = newContext();
        final ClientRequestContext expectedCtx = currentCtx;
        reqCtxAccessor.setValue(currentCtx);

        final ContextSnapshotFactory factory = ContextSnapshotFactory.builder()
                                                                     .clearMissing(true)
                                                                     .build();
        final ContextSnapshot contextSnapshot = factory.captureAll();
        reqCtxAccessor.setValue();

        // When : contextSnapshot.setThreadLocals()
        try (Scope ignored = contextSnapshot.setThreadLocals()) {

            // Then : should not
            final RequestContext reqCtxInScope = RequestContext.currentOrNull();
            assertThat(reqCtxInScope).isSameAs(expectedCtx);
        }

        // Then
        final RequestContext reqCtxOutOfScope = RequestContext.currentOrNull();
        assertThat(reqCtxOutOfScope).isNull();
    }

    static ClientRequestContext newContext() {
        return ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
