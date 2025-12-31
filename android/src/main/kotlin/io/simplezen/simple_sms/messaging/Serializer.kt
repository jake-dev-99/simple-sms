package io.simplezen.simple_sms.messaging

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AnySerializer : KSerializer<Any?> {
    private val json = Json {
        allowStructuredMapKeys = true
        ignoreUnknownKeys = true
        isLenient = true
//        prettyPrint = true
    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AnyType")

    fun decodeFromString(value: String): Any? {
        if (value.isBlank()) return null
        val jsonElement = json.parseToJsonElement(fixJsonFormat(value))
        return decodeJsonElement(jsonElement)
    }

    private fun decodeJsonElement(element: JsonElement): Any? {
        return when (element) {
            is JsonObject -> {
                element.mapValues { (_, value) -> decodeJsonElement(value) }
            }
            is JsonArray -> {
                element.map { decodeJsonElement(it) }
            }
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "null" -> null
                    element.content.toIntOrNull() != null -> element.content.toInt()
                    element.content.toDoubleOrNull() != null -> element.content.toDouble()
                    element.content.toBooleanStrictOrNull() != null -> element.content.toBoolean()
                    else -> element.content
                }
            }
            JsonNull -> null
        }
    }

    fun encodeToString(value: Any?): String {
        val jsonElement = toJsonElement(value)
        return jsonElement.toString()
    }

    override fun serialize(encoder: Encoder, value: Any?) {
        val jsonElement = toJsonElement(value)
        when (jsonElement) {
            is JsonPrimitive -> {
                when {
                    jsonElement.isString -> encoder.encodeString(jsonElement.content)
                    jsonElement.content.toIntOrNull() != null -> encoder.encodeInt(jsonElement.content.toInt())
                    jsonElement.content.toDoubleOrNull() != null -> encoder.encodeDouble(jsonElement.content.toDouble())
                    jsonElement.content.toBooleanStrictOrNull() != null -> encoder.encodeBoolean(jsonElement.content.toBoolean())
                    else -> encoder.encodeString(jsonElement.content)
                }
            }
            else -> encoder.encodeString(jsonElement.toString())
        }
    }

    private fun toJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is Map<*, *> -> {
                JsonObject(value.entries.associate { (k, v) ->
                    k.toString() to toJsonElement(v)
                })
            }
            is List<*> -> JsonArray(value.map { toJsonElement(it) })
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Any? {
        val string = decoder.decodeString()
        if (string.isBlank()) return null
        val element = json.parseToJsonElement(string)
        return decodeJsonElement(element)
    }

    private fun fixJsonFormat(input: String): String {
        if (input.startsWith("{") && input.contains("uri:")) {
            val uriPattern = "\\{uri:(.+)\\}".toRegex()
            val matchResult = uriPattern.find(input)

            if (matchResult != null) {
                val uriValue = matchResult.groupValues[1].toString()
                return "{\"uri\":\"$uriValue\"}"
            }
        }
        return input
    }
}