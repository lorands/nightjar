package dev.nightjar.domainevent.jdbc

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/**
 * Encodes envelope metadata as `key=value` lines with URL-escaping — readable
 * in the database, dependency-free, no JSON library needed.
 */
internal object MetadataCodec {

    fun encode(metadata: Map<String, String>): String? =
        if (metadata.isEmpty()) {
            null
        } else {
            metadata.entries.joinToString("\n") { (key, value) ->
                URLEncoder.encode(key, UTF_8) + "=" + URLEncoder.encode(value, UTF_8)
            }
        }

    fun decode(text: String?): Map<String, String> =
        text?.takeIf { it.isNotBlank() }
            ?.lineSequence()
            ?.associate { line ->
                val separator = line.indexOf('=')
                require(separator >= 0) { "Malformed metadata line: $line" }
                URLDecoder.decode(line.substring(0, separator), UTF_8) to
                    URLDecoder.decode(line.substring(separator + 1), UTF_8)
            }
            ?: emptyMap()
}
