/*
 * Copyright 2022 LINE Corporation
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

package example.armeria.server.blog.thrift;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

import example.armeria.blog.thrift.BlogNotFoundException;

public class BlogServiceExceptionHandler implements BiFunction<ServiceRequestContext, Throwable, RpcResponse> {

    @Override
    public RpcResponse apply(ServiceRequestContext serviceRequestContext, Throwable cause) {
        if (cause instanceof NullPointerException) {
            return RpcResponse.ofFailure(new BlogNotFoundException(cause.getMessage()));
        }
        return RpcResponse.ofFailure(cause);
    }
}
