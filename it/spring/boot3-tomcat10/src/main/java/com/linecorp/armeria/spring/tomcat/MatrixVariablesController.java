/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.spring.tomcat;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.collect.ImmutableList;

@RestController
public final class MatrixVariablesController {

    // GET /owners/42;q=11/pets/21;q=22
    // q1 = 11, q2 = 22

    @GetMapping("/owners/{ownerId}/pets/{petId}")
    List<Integer> findPet(
            @MatrixVariable(name = "q", pathVar = "ownerId") int q1,
            @MatrixVariable(name = "q", pathVar = "petId") int q2) {
        return ImmutableList.of(q1, q2);
    }
}
