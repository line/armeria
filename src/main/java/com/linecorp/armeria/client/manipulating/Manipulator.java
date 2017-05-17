package com.linecorp.armeria.client.manipulating;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Consumer;
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

/**
 * Manipulates some object(request) with a chain of operation.
 */
public class Manipulator<T> {

    private List<Consumer<T>> operations;

    /**
     * constructor.
     *
     * @param operations A list of {@link Consumer} objects. Each consumer (operation)
     *                   can update a request.
     *
     * @throws NullPointerException when operations are null.
     */
    public Manipulator(List<Consumer<T>> operations) {
        requireNonNull(operations);
        this.operations = operations;
    }
    
    void manipulate(T target) {
        operations.forEach(op -> op.accept(target));
    }

}
