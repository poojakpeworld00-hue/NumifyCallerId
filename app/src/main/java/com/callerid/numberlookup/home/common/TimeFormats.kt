package com.callerid.numberlookup.home.common

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/**
 * Clock formatting that follows the phone, not a hard-coded pattern.
 *
 * Every time the app printed used `"h:mm a"`, so a user whose device is set to
 * 24-hour time saw "9:05 PM" here and "21:05" everywhere else on their phone.
 * [DateFormat.getTimeFormat] reads the system's own 12/24-hour setting and the
 * locale's clock conventions together, which is the pair that has to agree —
 * picking `"HH:mm"` by hand would get the hour right and the separator wrong in
 * the locales that do not use a colon.
 *
 * Not cached. The formatter is cheap next to the row it fills, and the setting
 * can change under a running app — Android's own Settings toggle does exactly
 * that — so re-reading it per call is what keeps an open list honest.
 */
object TimeFormats {

    /** The wall-clock time of [millis], 12- or 24-hour as the device is set. */
    fun clock(context: Context, millis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(millis))
}
