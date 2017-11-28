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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;

public class DefaultRequestLogTest {

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private RequestContext ctx;

    private DefaultRequestLog log;

    @Before
    public void setUp() {
        log = new DefaultRequestLog(ctx);
    }

    @Test
    public void endResponseSuccess() {
        log.endResponse();
        assertThat(log.responseCause()).isNull();
    }

    @Test
    public void endResponseFailure() {
        Throwable error = new Throwable("response failed");
        log.endResponse(error);
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    public void rpcFailure_endResponseWithoutCause() {
        Throwable error = new Throwable("response failed");
        log.responseContent(RpcResponse.ofFailure(error), null);
        // If user code doesn't call endResponse, the framework automatically does with no cause.
        log.endResponse();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    public void rpcFailure_endResponseDifferentCause() {
        Throwable error = new Throwable("response failed one way");
        Throwable error2 = new Throwable("response failed a different way?");
        log.responseContent(RpcResponse.ofFailure(error), null);
        log.endResponse(error2);
        assertThat(log.responseCause()).isSameAs(error2);
    }
}
