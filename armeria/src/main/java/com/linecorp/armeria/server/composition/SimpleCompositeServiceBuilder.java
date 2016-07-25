/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.composition;

import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;

/**
 * A general purpose {@link AbstractCompositeServiceBuilder} implementation. Useful when you do not want to
 * define a new dedicated {@link Service} builder type.
 */
public final class SimpleCompositeServiceBuilder
        extends AbstractCompositeServiceBuilder<SimpleCompositeServiceBuilder> {

    @Override
    public SimpleCompositeServiceBuilder serviceAt(String exactPath, Service service) {
        return super.serviceAt(exactPath, service);
    }

    @Override
    public SimpleCompositeServiceBuilder serviceUnder(String pathPrefix, Service service) {
        return super.serviceUnder(pathPrefix, service);
    }

    @Override
    public SimpleCompositeServiceBuilder service(PathMapping pathMapping, Service service) {
        return super.service(pathMapping, service);
    }

    /**
     * Creates a new {@link SimpleCompositeService} with the {@link Service}s added by the {@code service*()}
     * methods.
     */
    public SimpleCompositeService build() {
        return new SimpleCompositeService(services());
    }
}
