/*
 * Copyright 2016 LINE Corporation
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

import java.util.List;

import com.linecorp.armeria.server.PathMapped;
import com.linecorp.armeria.server.Service;

/**
 * A general purpose {@link AbstractCompositeService} implementation. Useful when you do not want to define
 * a new dedicated {@link Service} type.
 */
public class SimpleCompositeService extends AbstractCompositeService {

    public SimpleCompositeService(CompositeServiceEntry... services) {
        super(services);
    }

    public SimpleCompositeService(List<CompositeServiceEntry> services) {
        super(services);
    }

    @Override
    public final List<CompositeServiceEntry> services() {
        return super.services();
    }

    @Override
    public <T extends Service> T serviceAt(int index) {
        return super.serviceAt(index);
    }

    @Override
    public PathMapped<Service> findService(String path) {
        return super.findService(path);
    }
}
