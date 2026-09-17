package com.callerid.numberlookup.home.feature.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityAiHubBinding
import com.callerid.numberlookup.home.entity.AiAction
import com.callerid.numberlookup.home.entity.AiActionKind
import com.callerid.numberlookup.home.entity.AiMessage
import com.callerid.numberlookup.home.entity.AiSuggestion
import com.callerid.numberlookup.home.feature.calldetails.CallDetailsActivity
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.delivery.RewardedAdPresenter
import com.callerid.numberlookup.home.feature.blocklist.BlockReward
import com.callerid.numberlookup.home.repository.BlocklistRepository
import com.callerid.numberlookup.home.repository.assistant.AiAssistantRepository
import com.callerid.numberlookup.home.repository.assistant.AskIntent
import kotlinx.coroutines.launch

/**
 * The Ask AI hub and transcript — screens 2 and 3 of the assistant flow, in one
 * Activity because they are the same surface in two states.
 *
 * It opens on ranked starter chips rather than an empty composer
 * ([AiSuggestionEngine] decides the ranking), and every answer carries its own
 * actions, so the insight and the thing to do about it stay together.
 */
class AiHubActivity : BaseActivity<ActivityAiHubBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "AiHubActivity"

    override val layoutId: Int = R.layout.activity_ai_hub

    private val repository by lazy { AiAssistantRepository(this) }
    private val suggestionEngine by lazy { AiSuggestionEngine(this) }
    private val blocklist by lazy { BlocklistRepository(this) }

    private val chatAdapter = AiChatAdapter(
        onAction = ::runAction,
        onFollowUp = { submit(it) }
    )
    private val suggestionAdapter = AiSuggestionAdapter { submit(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.aiRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The composer sits on the bottom edge, so it has to clear the IME
            // as well as the nav bar or it hides behind the keyboard.
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        binding.buttonAiBack.setOnClickListener { performBack() }
        binding.buttonAiSettings.setOnClickListener {
            startActivity(AiSettingsActivity.newIntent(this))
        }

        binding.listAiSuggestions.apply {
            layoutManager = LinearLayoutManager(this@AiHubActivity)
            adapter = suggestionAdapter
            isNestedScrollingEnabled = false
        }
        binding.listAiChat.apply {
            layoutManager = LinearLayoutManager(this@AiHubActivity)
            adapter = chatAdapter
        }

        binding.buttonAiSend.setOnClickListener { submitFromInput() }
        binding.inputAiQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitFromInput()
                true
            } else {
                false
            }
        }

        loadSuggestions()

        // The assistant offers "Block this number" as an action, so warm the ad
        // BlockReward shows once the free block slots are gone.
        RewardedAdPresenter.preload(this)
    }

    /**
     * Suggestions are ranked from the call log, so they are read off the main
     * thread and re-read on every entry — a spam call that landed while the user
     * was elsewhere should be the first chip when they come back.
     */
    private fun loadSuggestions() {
        lifecycleScope.launch {
            val ranked = runCatching { suggestionEngine.suggestions() }.getOrDefault(emptyList())
            suggestionAdapter.submit(ranked)
        }
    }

    private fun submitFromInput() {
        val text = binding.inputAiQuestion.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        binding.inputAiQuestion.text?.clear()
        submit(text)
    }

    /**
     * A chip the app offered. It is asked by its intent rather than by its text:
     * the label is localized and the on-device matcher reads English, so a chip
     * re-parsed from its own words answered nothing outside an English locale.
     */
    private fun submit(suggestion: AiSuggestion) {
        submit(suggestion.label, suggestion.intent, suggestion.subject)
    }

    private fun submit(question: String, intent: AskIntent? = null, subject: String = "") {
        showTranscript()
        chatAdapter.add(AiMessage.Question(question))
        chatAdapter.add(AiMessage.Thinking)
        scrollToEnd()

        lifecycleScope.launch {
            val answer = runCatching { repository.ask(question, intent, subject) }.getOrElse {
                AiMessage.Answer(getString(R.string.ai_ans_not_configured))
            }
            chatAdapter.replaceThinking(answer)
            scrollToEnd()
        }
    }

    /** The empty state is replaced, not hidden behind, once a question lands. */
    private fun showTranscript() {
        if (binding.listAiChat.isVisible) return
        binding.columnAiEmpty.visibility = View.GONE
        binding.listAiChat.visibility = View.VISIBLE
    }

    private fun scrollToEnd() {
        binding.listAiChat.post {
            val last = (binding.listAiChat.adapter?.itemCount ?: 0) - 1
            if (last >= 0) binding.listAiChat.smoothScrollToPosition(last)
        }
    }

    // --- actions ------------------------------------------------------------

    private fun runAction(action: AiAction) {
        when (action.kind) {
            AiActionKind.BLOCK -> BlockReward.allow(this, action.number) {
                blocklist.add(action.number)
                Toast.makeText(
                    this,
                    getString(R.string.ai_blocked_toast, action.number),
                    Toast.LENGTH_SHORT
                ).show()
            }

            AiActionKind.CALL -> placeCall(action.number)

            AiActionKind.DETAILS ->
                startActivity(CallDetailsActivity.newIntent(this, action.number, null))

            /**
             * Hands the draft to the user's own SMS app, pre-filled, and stops
             * there. Sending in-process would need SEND_SMS — a restricted
             * permission whose declaration would drag the app's existing
             * READ_CALL_LOG approval back through review. Same approach as
             * CallDetailsActivity and ReportNumberActivity already use.
             */
            AiActionKind.MESSAGE -> {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${action.number}")
                    putExtra("sms_body", action.messageBody)
                }
                runCatching { startActivity(intent) }.onFailure {
                    Toast.makeText(this, R.string.toast_no_sms_app, Toast.LENGTH_SHORT).show()
                }
            }

            AiActionKind.REPORT ->
                startActivity(CallDetailsActivity.newIntent(this, action.number, null))
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, AiHubActivity::class.java)
    }
}
