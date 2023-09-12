/*
 * Copyright 2021 LINE Corporation
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

package testing.resteasy.jaxrs.samples;

import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import testing.resteasy.jaxrs.samples.books.BookService;

@ApplicationPath("/app")
public class JaxRsApp extends Application {

    private final Set<Class<?>> classes = new HashSet<>();
    private final Set<Object> singletons = new HashSet<>();

    public JaxRsApp() {
        // adding classes for stateless services
        classes.add(CalculatorService.class);

        // adding singletons for stateful services
        final BookService bookService = new BookService();
        bookService.start();
        singletons.add(bookService);
        //singletons.add(new CalculatorService());
    }

    @Override
    public Set<Class<?>> getClasses() {
        return classes;
    }

    @Override
    public Set<Object> getSingletons() {
        return singletons;
    }

    @Override
    public String toString() {
        return "Sample JAX-RS App";
    }
}
