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

package com.linecorp.armeria.server.kotlin.logging

import com.linecorp.armeria.common.logging.ContentPreviewerFactory
import com.linecorp.armeria.common.logging.ContentPreviewerFactoryBuilder
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.logging.ContentPreviewingService
import java.nio.charset.Charset

/**
 * Decorates this [HttpService] using a [ContentPreviewingService] with the given [maxLength].
 */
fun HttpService.contentPreviewing(maxLength: Int): ContentPreviewingService =
    decorate(ContentPreviewingService.newDecorator(maxLength))

/**
 * Decorates this [HttpService] using a [ContentPreviewingService] with the given [maxLength] and [defaultCharset].
 */
fun HttpService.contentPreviewing(maxLength: Int, defaultCharset: Charset): ContentPreviewingService =
    decorate(ContentPreviewingService.newDecorator(maxLength, defaultCharset))

/**
 * Decorates this [HttpService] using a [ContentPreviewingService] with a [ContentPreviewerFactory] configured
 * by the given [block].
 */
fun HttpService.contentPreviewing(block: ContentPreviewerFactoryBuilder.() -> Unit): ContentPreviewingService =
    contentPreviewing(ContentPreviewerFactory.builder().apply(block).build())

/**
 * Decorates this [HttpService] using a [ContentPreviewingService] with the given [contentPreviewerFactory].
 */
fun HttpService.contentPreviewing(contentPreviewerFactory: ContentPreviewerFactory): ContentPreviewingService =
    decorate(ContentPreviewingService.newDecorator(contentPreviewerFactory))

/**
 * Decorates all [HttpService]s using a [ContentPreviewingService] with the given [maxLength].
 */
fun ServerBuilder.contentPreviewing(maxLength: Int): ServerBuilder =
    decorator(ContentPreviewingService.newDecorator(maxLength))

/**
 * Decorates all [HttpService]s using a [ContentPreviewingService] with the given [maxLength] and
 * [defaultCharset].
 */
fun ServerBuilder.contentPreviewing(maxLength: Int, defaultCharset: Charset): ServerBuilder =
    decorator(ContentPreviewingService.newDecorator(maxLength, defaultCharset))

/**
 * Decorates all [HttpService]s using a [ContentPreviewingService] with a [ContentPreviewerFactory] configured
 * by the given [block].
 */
fun ServerBuilder.contentPreviewing(block: ContentPreviewerFactoryBuilder.() -> Unit): ServerBuilder =
    contentPreviewing(ContentPreviewerFactory.builder().apply(block).build())

/**
 * Decorates all [HttpService]s using a [ContentPreviewingService] with the given [contentPreviewerFactory].
 */
fun ServerBuilder.contentPreviewing(contentPreviewerFactory: ContentPreviewerFactory): ServerBuilder =
    decorator(ContentPreviewingService.newDecorator(contentPreviewerFactory))
