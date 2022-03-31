package com.linecorp.armeria.common.thrifty

import com.linecorp.armeria.common.SerializationFormat

object ThriftySerializationFormats {

    @JvmStatic
    val BINARY: SerializationFormat = SerializationFormat.of("tbinary")

    @JvmStatic
    val COMPACT: SerializationFormat = SerializationFormat.of("tcompact")

    @JvmStatic
    val JSON: SerializationFormat = SerializationFormat.of("tjson")

    @JvmStatic
    val TEXT: SerializationFormat = SerializationFormat.of("ttext")

    @JvmStatic
    val TEXT_NAMED_ENUM: SerializationFormat = SerializationFormat.of("ttext-named-enum")

    fun isThrift(format: SerializationFormat): Boolean {
        return values.contains(format)
    }

    val values = setOf(BINARY, COMPACT, JSON, TEXT, TEXT_NAMED_ENUM)

}