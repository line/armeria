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

package com.linecorp.armeria.client;

import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.DefaultRpcRequest;
import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

public class ClientDecorationBuilderTest {

    /**
     * Make sure only {@link HttpRequest} and {@link HttpResponse} or {@link RpcRequest} and {@link RpcRequest}
     * are allowed.
     */
    @Test
    public void typeConstraints() {
        final ClientDecorationBuilder cdb = new ClientDecorationBuilder();
        assertThatThrownBy(() -> cdb.add(Request.class, Response.class, identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cdb.add(HttpRequest.class, RpcResponse.class, identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cdb.add(RpcRequest.class, HttpResponse.class, identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cdb.add(DefaultHttpRequest.class, DefaultHttpResponse.class, identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cdb.add(DefaultRpcRequest.class, DefaultRpcResponse.class, identity()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
