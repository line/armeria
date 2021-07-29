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

package com.linecorp.armeria.spring.tomcat.demo;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.collect.ImmutableMap;

@RestController
@RequestMapping("/error-handling")
public class ErrorHandlingController {

    @GetMapping("/runtime-exception")
    public void runtimeException() {
        throw new RuntimeException("runtime exception");
    }

    @GetMapping("/custom-exception")
    public void customException() {
        throw new CustomException();
    }

    @GetMapping("/exception-handler")
    public void exceptionHandler() {
        throw new BaseException("exception handler");
    }

    @ResponseStatus(code = HttpStatus.NOT_FOUND, reason = "custom not found")
    private static class CustomException extends RuntimeException {}

    private static class BaseException extends RuntimeException {
        BaseException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<Map<String, Object>> onBaseException(Throwable t) {
        final Map<String, Object> body = ImmutableMap.<String, Object>builder()
                                                     .put("status", HttpStatus.INTERNAL_SERVER_ERROR.value())
                                                     .put("message", t.getMessage())
                                                     .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
