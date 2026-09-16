package com.callerid.numberlookup.home.feature.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivitySmartReplyBinding
import com.callerid.numberlookup.home.entity.AiSuggestion

/**
 * Pick a drafted reply to a missed call, then hand it to the user's own SMS app.
 *
 * This is where the "AI answers for you" idea actually lands. It cannot send:
 * sending in-process needs SEND_SMS, a restricted permission whose declaration
 * would put the app's already-approved READ_CALL_LOG back through Play review.
 * So the app writes the message and `ACTION_SENDTO` opens Messages with it
 * ready — one tap from the user, and no permission at all.
 *
 * Deliberately a plain [AppCompatActivity] rather than the project's
 * [com.callerid.numberlookup.home.foundation.BaseActivity]: it is launched from a
 * notification, is on screen for a couple of seconds, and must not pull in the
 * ad and consent machinery a normal screen carries.
 */
class SmartReplyActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmartReplyBinding

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }
    private val callerName by lazy { intent.getStringExtra(EXTRA_NAME) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmartReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The notification's job is done the moment its action is taken.
        NotificationManagerCompat.from(this).cancel(MissedCallNotifier.notificationId(number))

        if (number.isBlank()) {
            finish()
            return
        }

        binding.textSmartReplySubtitle.text =
            callerName?.takeIf(String::isNotBlank) ?: number

        val adapter = AiSuggestionAdapter { hand(it.query) }
        binding.listSmartReplies.apply {
            layoutManager = LinearLayoutManager(this@SmartReplyActivity)
            this.adapter = adapter
        }
        adapter.submit(drafts())

        binding.buttonSmartReplyClose.setOnClickListener { finish() }
        binding.scrimSmartReply.setOnClickListener { finish() }
    }

    /**
     * Three drafts covering the answers a missed call actually has: I cannot
     * talk, I will call back, or write instead. The first is highlighted as the
     * default because it fits any caller, known or not.
     */
    private fun drafts(): List<AiSuggestion> = listOf(
        draft(R.string.ai_draft_missed_reply, 1, highlighted = true),
        draft(R.string.ai_draft_call_back, 2),
        draft(R.string.ai_draft_text_instead, 3)
    )

    private fun draft(resId: Int, priority: Int, highlighted: Boolean = false): AiSuggestion {
        val text = getString(resId)
        return AiSuggestion(text, text, priority, highlighted)
    }

    /** Opens the user's SMS app with the recipient and body already filled in. */
    private fun hand(message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.toast_no_sms_app, Toast.LENGTH_SHORT).show() }
        finish()
    }

    companion object {
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_NAME = "extra_name"

        fun newIntent(context: Context, number: String, name: String?): Intent =
            Intent(context, SmartReplyActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
