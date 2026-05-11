/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.annotation.DefaultValues;

/**
 * Annotation for binding a multipart body part to a method parameter.
 * Unlike {@link Param}, which treats multipart parts as simple strings or files,
 * this annotation uses the {@code Content-Type} header of each part to determine
 * how to deserialize the content. For example, a part with
 * {@code Content-Type: application/json} will be deserialized using Jackson.
 *
 * <h2>Supported types:</h2>
 * <ul>
 *   <li>{@link java.io.File}, {@link java.nio.file.Path},
 *       {@link com.linecorp.armeria.common.multipart.MultipartFile} &mdash; file upload</li>
 *   <li>{@link String} &mdash; raw text content of the part</li>
 *   <li>{@code byte[]} &mdash; raw binary content of the part</li>
 *   <li>Any other type &mdash; deserialized from JSON via Jackson.
 *       The part must have {@code Content-Type: application/json}.</li>
 * </ul>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * @Post("/upload")
 * @Consumes(MediaTypeNames.MULTIPART_FORM_DATA)
 * public HttpResponse upload(@Part MyBean metadata,
 *                            @Part MultipartFile file,
 *                            @Param String title) {
 *     ...
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@UnstableApi
public @interface Part {

    /**
     * The name of the multipart body part to bind to.
     */
    String value() default DefaultValues.UNSPECIFIED;
}
