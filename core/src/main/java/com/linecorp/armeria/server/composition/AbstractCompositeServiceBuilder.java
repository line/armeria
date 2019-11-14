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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;

/**
 * A skeletal builder implementation for {@link AbstractCompositeService} and its subclasses.
 * Extend this class to implement your own {@link Service} builder. e.g.
 * <pre>{@code
 * public class MyServiceBuilder extends AbstractCompositeServiceBuilder<MyServiceBuilder> {
 *
 *     private int propertyA;
 *     private String propertyB;
 *
 *     public MyServiceBuilder propertyA(int propertyA) {
 *         this.propertyA = propertyA;
 *         return this;
 *     }
 *
 *     public MyServiceBuilder propertyB(String propertyB) {
 *         this.propertyB = propertyB;
 *         return this;
 *     }
 *
 *     public MyService build() {
 *         serviceUnder("/foo/", new FooService(propertyA));
 *         serviceUnder("/bar/", new BarService(propertyB));
 *         serviceUnder("/", new OtherService());
 *
 *         List<CompositeServiceEntry> services = services();
 *         return new MyService(services);
 *     }
 * }
 *
 * public class MyService extends AbstractCompositeService {
 *     MyService(Iterable<CompositeServiceEntry> services) {
 *         super(services);
 *         ...
 *     }
 * }
 * }</pre>
 *
 * @see CompositeServiceEntry
 */
public abstract class AbstractCompositeServiceBuilder<T extends Service<?, ?>> {

    private final List<CompositeServiceEntry<T>> services = new ArrayList<>();
    private final List<CompositeServiceEntry<T>> unmodifiableServices = Collections.unmodifiableList(services);

    /**
     * Creates a new instance.
     */
    protected AbstractCompositeServiceBuilder() {}

    /**
     * Returns the list of the {@link CompositeServiceEntry}s added via {@link #service(String, Service)},
     * {@link #serviceUnder(String, Service)}, {@link #service(Route, Service)} and
     * {@link #service(CompositeServiceEntry)}.
     */
    protected final List<CompositeServiceEntry<T>> services() {
        return unmodifiableServices;
    }

    /**
     * Binds the specified {@link Service} under the specified directory..
     */
    protected AbstractCompositeServiceBuilder<T> serviceUnder(String pathPrefix, T service) {
        return service(CompositeServiceEntry.ofPrefix(pathPrefix, service));
    }

    /**
     * Binds the specified {@link Service} at the specified path pattern. e.g.
     * <ul>
     *   <li>{@code /login} (no path parameters)</li>
     *   <li>{@code /users/{userId}} (curly-brace style)</li>
     *   <li>{@code /list/:productType/by/:ordering} (colon style)</li>
     *   <li>{@code exact:/foo/bar} (exact match)</li>
     *   <li>{@code prefix:/files} (prefix match)</li>
     *   <li><code>glob:/~&#42;/downloads/**</code> (glob pattern)</li>
     *   <li>{@code regex:^/files/(?<filePath>.*)$} (regular expression)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    protected AbstractCompositeServiceBuilder<T> service(String pathPattern, T service) {
        return service(CompositeServiceEntry.of(pathPattern, service));
    }

    /**
     * Binds the specified {@link Service} at the specified {@link Route}.
     */
    protected AbstractCompositeServiceBuilder<T> service(Route route, T service) {
        return service(CompositeServiceEntry.of(route, service));
    }

    /**
     * Binds the specified {@link CompositeServiceEntry}.
     */
    protected AbstractCompositeServiceBuilder<T> service(CompositeServiceEntry<T> entry) {
        services.add(requireNonNull(entry, "entry"));
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' + services() + ')';
    }
}
