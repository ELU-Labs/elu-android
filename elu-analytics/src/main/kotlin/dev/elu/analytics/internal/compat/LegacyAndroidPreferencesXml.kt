package dev.elu.analytics.internal.compat

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** Reads the actual Android SharedPreferences map format, with no provider dependency. */
internal object LegacyAndroidPreferencesXml {
    fun decode(documentText: String, parser: XmlPullParser): Map<String, Any?> {
        try {
            if (documentText.toByteArray(Charsets.UTF_8).size > 262_144) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_TOO_LARGE)
            // Android KXml reports unsupported features as false and refuses their setters.
            // Require declarations disabled and reject a DTD before the parser consumes it.
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL) || documentText.contains("<!DOCTYPE")) bad()
            parser.setInput(StringReader(documentText))
            if (nextTag(parser) != XmlPullParser.START_TAG || parser.name != "map" || parser.attributeCount != 0) bad()
            val result = linkedMapOf<String, Any?>()
            while (true) {
                val event = nextTag(parser)
                if (event == XmlPullParser.END_TAG) {
                    if (parser.name != "map") bad()
                    break
                }
                if (event != XmlPullParser.START_TAG || result.size >= 4096) bad()
                val type = parser.name
                val expected = if (type in setOf("string", "set")) setOf("name") else setOf("name", "value")
                if ((0 until parser.attributeCount).map { parser.getAttributeName(it) }.toSet() != expected || parser.attributeCount != expected.size) bad()
                if ((0 until parser.attributeCount).any { !parser.getAttributeNamespace(it).isNullOrEmpty() }) bad()
                val name = parser.getAttributeValue(null, "name") ?: bad()
                if (result.containsKey(name)) bad()
                val raw = parser.getAttributeValue(null, "value")
                val value: Any? = when (type) {
                    "string" -> text(parser, "string")
                    "set" -> stringSet(parser)
                    "boolean" -> when (raw) { "true" -> true; "false" -> false; else -> bad() }.also { end(parser, type) }
                    "int" -> raw?.toIntOrNull()?.also { end(parser, type) } ?: bad()
                    "long" -> raw?.toLongOrNull()?.also { end(parser, type) } ?: bad()
                    "float" -> raw?.toFloatOrNull()?.takeIf { it.isFinite() }?.also { end(parser, type) } ?: bad()
                    else -> bad()
                }
                result[name] = value
            }
            if (nextTag(parser) != XmlPullParser.END_DOCUMENT) bad()
            return result
        } catch (error: AndroidLegacyStartupException) { throw error }
        catch (_: Exception) { bad() }
    }

    private fun stringSet(parser: XmlPullParser): Set<String> {
        val result = linkedSetOf<String>()
        while (true) {
            val event = nextTag(parser)
            if (event == XmlPullParser.END_TAG && parser.name == "set") return result
            if (event != XmlPullParser.START_TAG || parser.name != "string" || parser.attributeCount != 0 || result.size >= 4096) bad()
            if (!result.add(text(parser, "string"))) bad()
        }
    }

    private fun end(parser: XmlPullParser, name: String) {
        if (nextTag(parser) != XmlPullParser.END_TAG || parser.name != name) bad()
    }

    private fun text(parser: XmlPullParser, name: String): String {
        val result = StringBuilder()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_TAG -> { if (parser.name != name) bad(); return result.toString() }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> result.append(parser.text)
                XmlPullParser.ENTITY_REF -> {
                    val entity = parser.name ?: bad()
                    if (entity !in setOf("amp", "lt", "gt", "apos", "quot") && !entity.startsWith("#")) bad()
                    result.append(parser.text ?: bad())
                }
                else -> bad()
            }
        }
    }

    private fun nextTag(parser: XmlPullParser): Int {
        while (true) {
            val event = parser.nextToken()
            if (event == XmlPullParser.TEXT && parser.isWhitespace) continue
            if (event !in setOf(XmlPullParser.START_TAG, XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT)) bad()
            if (event != XmlPullParser.END_DOCUMENT && !parser.namespace.isNullOrEmpty()) bad()
            return event
        }
    }

    private fun bad(): Nothing = legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
}
