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

package com.linecorp.armeria.server.composition;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RpcService;

/**
 * A general purpose {@link AbstractCompositeServiceBuilder} implementation. Useful when you do not want to
 * define a new dedicated {@link RpcService} builder type.
 */
public final class SimpleCompositeRpcServiceBuilder extends AbstractCompositeServiceBuilder<RpcService> {

    SimpleCompositeRpcServiceBuilder() {}

    @Override
    public SimpleCompositeRpcServiceBuilder serviceUnder(String pathPrefix, RpcService service) {
        return (SimpleCompositeRpcServiceBuilder) super.serviceUnder(pathPrefix, service);
    }

    @Override
    public SimpleCompositeRpcServiceBuilder service(String pathPattern, RpcService service) {
        return (SimpleCompositeRpcServiceBuilder) super.service(pathPattern, service);
    }

    @Override
    public SimpleCompositeRpcServiceBuilder service(Route route, RpcService  service) {
        return (SimpleCompositeRpcServiceBuilder) super.service(route, service);
    }

    /**
     * Returns a newly-created {@link SimpleCompositeRpcService} based on the {@link RpcService}s added to this
     * builder.
     */
    public SimpleCompositeRpcService build() {
        return SimpleCompositeRpcService.of(services());
    }
}
