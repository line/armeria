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

package com.linecorp.armeria.server.kotlin.fild

import com.linecorp.armeria.server.file.FileService
import com.linecorp.armeria.server.file.FileServiceBuilder
import com.linecorp.armeria.server.file.HttpVfs
import java.io.File
import java.nio.file.Path

/**
 * Returns a new [FileService] configured by the given [block] with the given [rootDir].
 */
fun buildFileService(rootDir: File, block: FileServiceBuilder.() -> Unit): FileService =
    FileService.builder(rootDir).apply(block).build()

/**
 * Returns a new [FileService] configured by the given [block] with the given [rootDir].
 */
fun buildFileService(rootDir: Path, block: FileServiceBuilder.() -> Unit): FileService =
    FileService.builder(rootDir).apply(block).build()

/**
 * Returns a new [FileService] configured by the given [block] with the given [rootDir] in the given
 * [classLoader].
 */
fun buildFileService(
    classLoader: ClassLoader,
    rootDir: String,
    block: FileServiceBuilder.() -> Unit
): FileService = FileService.builder(classLoader, rootDir).apply(block).build()

/**
 * Returns a new [FileService] configured by the given [block] with the given [vfs].
 */
fun buildFileService(vfs: HttpVfs, block: FileServiceBuilder.() -> Unit): FileService =
    FileService.builder(vfs).apply(block).build()
