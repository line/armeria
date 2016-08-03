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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;

/**
 * A skeletal builder implementation for {@link AbstractCompositeService} and its subclasses.
 * Extend this class to implement your own {@link Service} builder. e.g.
 * <pre>{@code
 * public class MyServiceBuilder extends AbstractCompositeServiceBuilder&lt;MyServiceBuilder&gt; {
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
 *     MyService(Iterable&lt;CompositeServiceEntry&gt; services) {
 *         super(services);
 *         ...
 *     }
 * }
 * }</pre>
 *
 * @param <T> the self type
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 *
 * @see CompositeServiceEntry
 */
public abstract class AbstractCompositeServiceBuilder<T extends AbstractCompositeServiceBuilder<T, I, O>,
                                                      I extends Request, O extends Response> {

    private final List<CompositeServiceEntry<? super I, ? extends O>> services = new ArrayList<>();
    private final List<CompositeServiceEntry<? super I, ? extends O>> unmodifiableServices =
            Collections.unmodifiableList(services);

    /**
     * Creates a new instance.
     */
    protected AbstractCompositeServiceBuilder() {}

    /**
     * Returns {@code this} cast to the type {@code T}.
     */
    @SuppressWarnings("unchecked")
    protected final T self() {
        return (T) this;
    }

    /**
     * Returns the list of the {@link CompositeServiceEntry}s added via {@link #serviceAt(String, Service)},
     * {@link #serviceUnder(String, Service)}, {@link #service(PathMapping, Service)} and
     * {@link #service(CompositeServiceEntry)}.
     */
    protected final List<CompositeServiceEntry<? super I, ? extends O>> services() {
        return unmodifiableServices;
    }

    /**
     * Binds the specified {@link Service} at the specified exact path.
     */
    protected T serviceAt(String exactPath, Service<? super I, ? extends O> service) {
        return service(CompositeServiceEntry.ofExact(exactPath, service));
    }

    /**
     * Binds the specified {@link Service} under the specified directory..
     */
    protected T serviceUnder(String pathPrefix, Service<? super I, ? extends O> service) {
        return service(CompositeServiceEntry.ofPrefix(pathPrefix, service));
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping}.
     */
    protected T service(PathMapping pathMapping, Service<? super I, ? extends O> service) {
        return service(CompositeServiceEntry.of(pathMapping, service));
    }

    /**
     * Binds the specified {@link CompositeServiceEntry}.
     */
    protected T service(CompositeServiceEntry<? super I, ? extends O> entry) {
        requireNonNull(entry, "entry");
        @SuppressWarnings("unchecked")
        CompositeServiceEntry<I, O> cast = (CompositeServiceEntry<I, O>) entry;
        services.add(cast);
        return self();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' + services() + ')';
    }
}
