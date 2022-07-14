/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AbstractUnwrappableTest {

    @Test
    void testUnwrap() {
        final Foo foo = new Foo();
        assertThat(foo.unwrap()).isSameAs(foo);

        final Bar<Foo> bar = new Bar<>(foo);
        assertThat(bar.unwrap()).isSameAs(foo);

        final Qux<Bar<Foo>> qux = new Qux<>(bar);
        assertThat(qux.unwrap()).isSameAs(bar);
        assertThat(qux.unwrap().unwrap()).isSameAs(foo);
    }

    @Test
    void testUnwrapAll() {
        final Foo foo = new Foo();
        assertThat(foo.unwrapAll()).isSameAs(foo);

        final Bar<Foo> bar = new Bar<>(foo);
        assertThat(bar.unwrapAll()).isSameAs(foo);

        final Qux<Bar<Foo>> qux = new Qux<>(bar);
        assertThat(qux.unwrapAll()).isSameAs(foo);

        final Baz baz = new Baz(qux);
        assertThat(baz.unwrapAll()).isSameAs(foo);
    }

    private static final class Foo implements Unwrappable {}

    private static final class Bar<T extends Unwrappable> extends AbstractUnwrappable<T> {
        Bar(T delegate) {
            super(delegate);
        }
    }

    private static final class Baz implements Unwrappable {

        private final Unwrappable delegate;

        Baz(Unwrappable delegate) {
            this.delegate = delegate;
        }

        @Override
        public Unwrappable unwrap() {
            return delegate.unwrap();
        }
    }

    private static final class Qux<T extends Unwrappable> extends AbstractUnwrappable<T> {
        Qux(T delegate) {
            super(delegate);
        }
    }
}
