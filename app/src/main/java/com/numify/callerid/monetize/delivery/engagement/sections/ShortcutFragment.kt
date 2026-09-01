package com.numify.callerid.monetize.delivery.engagement.sections

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.common.triggerClick

class ShortcutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_third, container, false)
        view.findViewById<LinearLayout>(R.id.btnmessageVw).triggerClick {
            messga()
        }
        view.findViewById<LinearLayout>(R.id.padAddContact).triggerClick {
            addContact()
        }

        view.findViewById<LinearLayout>(R.id.padSendEmail).triggerClick {
            sendEmail()
        }

        view.findViewById<LinearLayout>(R.id.padAddCalendar).triggerClick {
            addCalendarEvent()
        }

        view.findViewById<LinearLayout>(R.id.padOpenWebsite).triggerClick {
            openWebsite()
        }

        return view
    }

    private fun messga() {
        val phoneNumber = "1234567890" // Replace with the recipient's number
        val messageText = "Hello! This is a test message."

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("sms:$phoneNumber")  // Use "smsto:" also works
            putExtra("sms_body", messageText)
        }

        val ctx = context ?: return
        if (intent.resolveActivity(ctx.packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(ctx, R.string.toast_no_sms_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addContact() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
        }

        val ctx = context ?: return
        if (intent.resolveActivity(ctx.packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(ctx, R.string.toast_no_contacts_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendEmail() {
        val email = "example@example.com"
        val subject = "Hello from the app"
        val message = "This is a sample message."

        val ctx = context ?: return

        // Build a mailto: intent — works with any installed email app
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, message)
        }

        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // No email app handles mailto: — fall back to a generic share chooser
            // with text/plain so WhatsApp, Messages, etc. can also accept it.
            // message/rfc822 is email-only and breaks non-email targets like WhatsApp.
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, message)
            }
            try {
                startActivity(Intent.createChooser(fallback, "Send via…"))
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(ctx, R.string.toast_no_share_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addCalendarEvent() {
        val startMillis = System.currentTimeMillis() + 60 * 60 * 1000 // 1 hour later
        val endMillis = startMillis + 60 * 60 * 1000 // 1-hour event

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Sample Event")
            putExtra(CalendarContract.Events.EVENT_LOCATION, "Office")
            putExtra(CalendarContract.Events.DESCRIPTION, "Discuss project")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        }

        val ctx = context ?: return
        try {
            if (intent.resolveActivity(ctx.packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(ctx, R.string.toast_no_calendar_app, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.toast_calendar_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebsite() {
        val url = "https://www.google.com"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        val ctx = context ?: return
        try {
            if (isChromeInstalled()) {
                intent.setPackage("com.android.chrome")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                // Fallback: try without Chrome package
                intent.setPackage(null)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(ctx, R.string.toast_no_browser_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isChromeInstalled(): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.packageManager.getPackageInfo("com.android.chrome", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
