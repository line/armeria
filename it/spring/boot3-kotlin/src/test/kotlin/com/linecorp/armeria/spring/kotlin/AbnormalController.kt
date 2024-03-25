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
package com.linecorp.armeria.spring.kotlin

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * This controller returns a JSON with an incorrect content type header.
 */
@Controller
class AbnormalController(private val objectMapper: ObjectMapper) {
    @GetMapping(value = ["/abnormal"], produces = ["text/plain;charset=utf-8"])
    @ResponseBody
    fun abnormal(): ResponseEntity<String> {
        return ResponseEntity.ok()
            .body(objectMapper.writeValueAsString(Abnormal(abnormal = true, dummyText = "output")))
    }
}

data class Abnormal(val abnormal: Boolean, val dummyText: String)
