package com.silvershireinn.app.utils

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun String.toInitials(): String {
    // Split on any whitespace (double spaces used to drop the second initial).
    val parts = this.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        else -> parts[0].take(2).uppercase()
    }
}

fun String.toAvatarColor(): Int {
    val colors = listOf(
        0xFFC9A84C.toInt(), 0xFF8B6914.toInt(), 0xFF7B6B3E.toInt(),
        0xFF5C7A5C.toInt(), 0xFF6B5C7A.toInt(), 0xFF7A5C5C.toInt()
    )
    return colors[this.hashCode().and(0x7FFFFFFF) % colors.size]
}

/**
 * Parse any ISO-8601 timestamp the app has ever stored (with or without milliseconds,
 * any fraction length, "Z" or "+HH:MM" offsets) to epoch millis. Returns null if unparseable.
 */
fun isoToMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?\s*(Z|[+-]\d{2}:?\d{2})?$""")
        .matchEntire(raw.trim()) ?: return null
    val g = m.groupValues
    return try {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(),
            if (g[6].isEmpty()) 0 else g[6].toInt())
        val ms = if (g[7].isEmpty()) 0 else (g[7] + "00").take(3).toInt()
        var t = cal.timeInMillis + ms
        val off = g[8]
        if (off.isNotEmpty() && off != "Z") {
            val sign = if (off[0] == '-') -1 else 1
            val digits = off.substring(1).replace(":", "")
            val mins = digits.take(2).toInt() * 60 + digits.drop(2).toInt()
            t -= sign * mins * 60_000L
        }
        t
    } catch (_: Exception) { null }
}

/** Canonical stored form: "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" in UTC, so string order == time order. */
fun millisToIso(ms: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(ms))
}

/** Normalize any timestamp to the canonical form; unparseable input becomes "now". */
fun canonicalTs(raw: String?): String = isoToMillis(raw)?.let { millisToIso(it) } ?: nowIso()

/** ThreadLocal that works on Android 7 (withInitial needs Android 8). */
private fun tl(make: () -> SimpleDateFormat) = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = make() }

// Formatters are reused per thread (creating them on every bind made scrolling slower).
private val tlDay = tl { SimpleDateFormat("yyyyMMdd", Locale.US) }
private val tlTime12 = tl { SimpleDateFormat("h:mm a", Locale.getDefault()) }
private val tlTime24 = tl { SimpleDateFormat("HH:mm", Locale.getDefault()) }
private val tlWeekday = tl { SimpleDateFormat("EEE", Locale.getDefault()) }
private val tlMonthDay = tl { SimpleDateFormat("MMM d", Locale.getDefault()) }
private val tlFullDate = tl { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
private val tlLongDate = tl { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }

private fun timeOf(d: Date): String =
    (if (PreferenceManager.use24HourClock) tlTime24.get()!! else tlTime12.get()!!).apply { timeZone = TimeZone.getDefault() }.format(d)

/** Whole calendar days between [d] and now in the phone's time zone (0 = today, 1 = yesterday). */
private fun daysAgo(d: Date): Int {
    val tz = TimeZone.getDefault()
    val a = Calendar.getInstance(tz).apply { time = d; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val b = Calendar.getInstance(tz).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
}

/**
 * Time shown on a message: "3:42 PM", "Yesterday 3:42 PM", "Mon 3:42 PM" (this week),
 * "Nov 3, 3:42 PM" (this year), "Nov 3, 2025" (older). Older messages used to show only a
 * date, so you could not tell when in the day they were sent.
 */
fun String.formatTimestamp(): String {
    return try {
        val date = Date(isoToMillis(this) ?: return this)
        // Setting: full day, date and time on every message ("Wed, Nov 19, 2026, 3:42 PM").
        if (PreferenceManager.showFullTimestamps) return "${tlLongDate.get()!!.format(date)}, ${timeOf(date)}"
        val days = daysAgo(date)
        val sameYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
        when {
            days <= 0 -> timeOf(date)
            days == 1 -> "Yesterday ${timeOf(date)}"
            days < 7 -> "${tlWeekday.get()!!.format(date)} ${timeOf(date)}"
            sameYear -> "${tlMonthDay.get()!!.format(date)}, ${timeOf(date)}"
            else -> "${tlFullDate.get()!!.format(date)}, ${timeOf(date)}"   // older ones had no time
        }
    } catch (e: Exception) { this }
}

/** Short time for lists (chat list): "3:42 PM", "Yesterday", "Mon", "Nov 3", "Nov 3, 2025". */
fun String.formatListTimestamp(): String {
    return try {
        val date = Date(isoToMillis(this) ?: return this)
        val days = daysAgo(date)
        val sameYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
        when {
            days <= 0 -> timeOf(date)
            days == 1 -> "Yesterday"
            days < 7 -> tlWeekday.get()!!.format(date)
            sameYear -> tlMonthDay.get()!!.format(date)
            else -> tlFullDate.get()!!.format(date)
        }
    } catch (e: Exception) { this }
}

fun nowIso(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}

/** Short text for an attachment in notifications / chat heads / previews. */
fun attachmentPreview(mime: String?, name: String?): String {
    val m = mime.orEmpty().lowercase()
    return when {
        m == "image/gif" -> "\uD83D\uDDBC GIF"
        m.startsWith("image/") -> "\uD83D\uDCF7 Photo"
        m.startsWith("video/") -> "\uD83C\uDFA5 Video"
        m.startsWith("audio/") -> "\uD83C\uDFA4 Voice message"
        else -> "\uD83D\uDCCE " + (name?.takeIf { it.isNotBlank() } ?: "File")
    }
}
