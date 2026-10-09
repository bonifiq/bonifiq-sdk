package br.com.bonifiq.sdk.ui.internal

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * Renderiza o HTML simples do regulamento (títulos, parágrafos, listas, negrito, itálico, links) como texto
 * estilizado — sem WebView. Tags desconhecidas são ignoradas e entidades (`&ccedil;`, `&#233;`) decodificadas.
 */
internal fun htmlToAnnotatedString(html: String): AnnotatedString = buildAnnotatedString {
    var bold = 0
    var italic = 0
    var heading = 0
    var link = 0
    var pendingBreaks = 0
    var atLineStart = true

    fun breakLines(count: Int) {
        if (length > 0) pendingBreaks = maxOf(pendingBreaks, count)
    }

    fun appendText(raw: String) {
        var text = decodeEntities(raw).replace(WHITESPACE, " ")
        if (atLineStart || pendingBreaks > 0) text = text.trimStart()
        if (text.isEmpty()) return
        if (pendingBreaks > 0) {
            append("\n".repeat(pendingBreaks))
            pendingBreaks = 0
        }
        val style = SpanStyle(
            fontWeight = if (bold > 0 || heading > 0) FontWeight.Bold else null,
            fontStyle = if (italic > 0) FontStyle.Italic else null,
            fontSize = if (heading > 0) 1.12.em else androidx.compose.ui.unit.TextUnit.Unspecified,
            textDecoration = if (link > 0) TextDecoration.Underline else null,
        )
        pushStyle(style)
        append(text)
        pop()
        atLineStart = false
    }

    TOKEN.findAll(html).forEach { match ->
        val token = match.value
        if (!token.startsWith("<")) {
            appendText(token)
            return@forEach
        }
        val closing = token.startsWith("</")
        val name = TAG_NAME.find(token)?.groupValues?.get(1)?.lowercase() ?: return@forEach
        val delta = if (closing) -1 else 1
        when (name) {
            "br" -> { if (length > 0) { append("\n"); atLineStart = true } }
            "p", "div" -> breakLines(2)
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                breakLines(2)
                heading = (heading + delta).coerceAtLeast(0)
            }
            "li" -> if (!closing) {
                breakLines(1)
                if (pendingBreaks > 0) {
                    append("\n".repeat(pendingBreaks))
                    pendingBreaks = 0
                }
                append("• ")
                atLineStart = true
            }
            "ul", "ol" -> breakLines(if (closing) 2 else 1)
            "strong", "b" -> bold = (bold + delta).coerceAtLeast(0)
            "em", "i" -> italic = (italic + delta).coerceAtLeast(0)
            "a" -> link = (link + delta).coerceAtLeast(0)
        }
    }
}

private val TOKEN = Regex("<[^>]+>|[^<]+")
private val TAG_NAME = Regex("^</?\\s*([a-zA-Z0-9]+)")
private val WHITESPACE = Regex("\\s+")
private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

internal fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
    val entity = match.groupValues[1]
    when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let(::codePointToString)
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePointToString)
        else -> NAMED_ENTITIES[entity]
    } ?: match.value
}

private fun codePointToString(codePoint: Int): String =
    if (codePoint <= 0xFFFF) codePoint.toChar().toString()
    else {
        val offset = codePoint - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
    "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
    "agrave" to "à", "Agrave" to "À", "acirc" to "â", "ecirc" to "ê", "ocirc" to "ô",
    "Acirc" to "Â", "Ecirc" to "Ê", "Ocirc" to "Ô", "atilde" to "ã", "otilde" to "õ",
    "Atilde" to "Ã", "Otilde" to "Õ", "ccedil" to "ç", "Ccedil" to "Ç", "uuml" to "ü", "Uuml" to "Ü",
    "ordm" to "º", "ordf" to "ª", "deg" to "°", "ndash" to "–", "mdash" to "—", "hellip" to "…",
    "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "bull" to "•", "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
)
