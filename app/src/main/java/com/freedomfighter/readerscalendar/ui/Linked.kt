package com.freedomfighter.readerscalendar.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import com.freedomfighter.readerscalendar.R

/**
 * The text of an event, with what can be acted on made tappable: a phone number opens the dialer
 * with the number ready (never dialled by itself), an address of mail the mail app, a web address
 * the browser. Everything else stays plain text — and the whole page can be selected and copied,
 * because these texts live inside a SelectionContainer.
 */

/** What was found in a text: where it is and what to do with it. */
data class Link(val start: Int, val end: Int, val uri: String)

private const val MIN_PHONE_DIGITS = 7      // shorter runs of digits are dates, prices, room numbers…
private const val MAX_PHONE_DIGITS = 15     // E.164

// Our own patterns rather than android.util.Patterns: those match loosely (a year is a phone
// number, a sentence's "etc.fr" a web address) and are not available to unit tests.
private val EMAIL = Regex("""[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
private val WEB = Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val PHONE = Regex("""(?<![\w@])\(?\+?\d[\d\u00a0 ()./\-]{4,}\d(?![\w])""")
private val DATE = Regex("""\d{1,4}[./\-]\d{1,2}[./\-]\d{2,4}""")

/** Phone numbers, mail addresses and web addresses of a text, in order, without overlaps. */
fun findLinks(text: String): List<Link> {
    val found = ArrayList<Link>()
    fun add(start: Int, end: Int, uri: String) {
        if (found.none { start < it.end && it.start < end }) found.add(Link(start, end, uri))
    }
    for (m in EMAIL.findAll(text)) add(m.range.first, m.range.last + 1, "mailto:" + m.value)
    for (m in WEB.findAll(text)) {
        val value = m.value.trimEnd('.', ',', ';', ':', ')', '!', '?')
        if (!value.contains('.')) continue
        val uri = if (value.contains("://")) value else "https://$value"
        add(m.range.first, m.range.first + value.length, uri)
    }
    for (m in PHONE.findAll(text)) {
        val value = m.value.trim().trimEnd('.', ',', ';', '-', '/')
        val digits = value.count { it.isDigit() }
        if (digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS) continue
        if (DATE.matches(value)) continue        // 17.09.2026 is a date, not a number to call
        add(m.range.first, m.range.first + value.length, "tel:" + value.replace(" ", "").replace("\u00a0", ""))
    }
    return found.sortedBy { it.start }
}

/** The intent a link opens: the dialer keeps the number on screen, it is never called. */
fun intentFor(uri: String): Intent = when {
    uri.startsWith("tel:") -> Intent(Intent.ACTION_DIAL, Uri.parse(uri))
    uri.startsWith("mailto:") -> Intent(Intent.ACTION_SENDTO, Uri.parse(uri))
    else -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
}

fun openLink(context: Context, uri: String) {
    try {
        context.startActivity(intentFor(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_this, Toast.LENGTH_SHORT).show()
    }
}

/** A place written in words: the maps app, on its name. */
fun placeUri(place: String): String = "geo:0,0?q=" + percent(place)

/** Percent-encoding of a query, written here so it can be tested without Android. */
fun percent(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c) else append("%%%02X".format(b))
    }
}

/**
 * [text] drawn like [T], with its phone numbers, mail and web addresses underlined and tappable.
 * [wholeAs] makes the whole text a link (a place opening the maps app) when nothing was found
 * inside it.
 */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = LocalTypo.current.tile,
    color: Color = LocalColors.current.fg,
    align: TextAlign = LocalTypo.current.textAlign,
    lineHeightMul: Float = 1.25f,
    wholeAs: String? = null
) {
    val context = LocalContext.current
    val links = findLinks(text)
    val styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    val annotated: AnnotatedString = buildAnnotatedString {
        if (links.isEmpty() && wholeAs != null) {
            withLink(LinkAnnotation.Clickable("place", styles) { openLink(context, wholeAs) }) { append(text) }
            return@buildAnnotatedString
        }
        var at = 0
        for (link in links) {
            if (link.start > at) append(text.substring(at, link.start))
            withLink(LinkAnnotation.Clickable(link.uri, styles) { openLink(context, link.uri) }) {
                append(text.substring(link.start, link.end))
            }
            at = link.end
        }
        if (at < text.length) append(text.substring(at))
    }
    BasicText(
        text = annotated,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontFamily = LocalTypo.current.family,
            fontWeight = LocalTypo.current.weight,
            fontSize = size,
            lineHeight = size * lineHeightMul,
            textAlign = align
        )
    )
}
