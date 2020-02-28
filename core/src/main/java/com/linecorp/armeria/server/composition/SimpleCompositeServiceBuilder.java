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

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;

/**
 * A general purpose {@link AbstractCompositeServiceBuilder} implementation. Useful when you do not want to
 * define a new dedicated {@link HttpService} builder type.
 */
public final class SimpleCompositeServiceBuilder extends AbstractCompositeServiceBuilder<HttpService> {

    SimpleCompositeServiceBuilder() {}

    @Override
    public SimpleCompositeServiceBuilder serviceUnder(String pathPrefix, HttpService  service) {
        return (SimpleCompositeServiceBuilder) super.serviceUnder(pathPrefix, service);
    }

    @Override
    public SimpleCompositeServiceBuilder service(String pathPattern, HttpService service) {
        return (SimpleCompositeServiceBuilder) super.service(pathPattern, service);
    }

    @Override
    public SimpleCompositeServiceBuilder service(Route route, HttpService  service) {
        return (SimpleCompositeServiceBuilder) super.service(route, service);
    }

    /**
     * Returns a newly-created {@link SimpleCompositeService} based on the {@link HttpService}s added to this
     * builder.
     */
    public SimpleCompositeService build() {
        return SimpleCompositeService.of(services());
    }
}
