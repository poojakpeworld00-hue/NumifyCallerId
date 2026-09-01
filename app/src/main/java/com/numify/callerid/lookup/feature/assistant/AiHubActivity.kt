package com.numify.callerid.lookup.feature.assistant

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
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.databinding.ActivityAiHubBinding
import com.numify.callerid.lookup.entity.AiAction
import com.numify.callerid.lookup.entity.AiActionKind
import com.numify.callerid.lookup.entity.AiMessage
import com.numify.callerid.lookup.feature.calldetails.CallDetailsActivity
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.assistant.AiAssistantRepository
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

    override val layoutId: Int = R.layout.activity_ai_hub

    private val repository by lazy { AiAssistantRepository(this) }
    private val suggestionEngine by lazy { AiSuggestionEngine(this) }
    private val blocklist by lazy { BlocklistRepository(this) }

    private val chatAdapter = AiChatAdapter(
        onAction = ::runAction,
        onFollowUp = ::submit
    )
    private val suggestionAdapter = AiSuggestionAdapter { submit(it.query) }

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

    private fun submit(question: String) {
        showTranscript()
        chatAdapter.add(AiMessage.Question(question))
        chatAdapter.add(AiMessage.Thinking)
        scrollToEnd()

        lifecycleScope.launch {
            val answer = runCatching { repository.ask(question) }.getOrElse {
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
            AiActionKind.BLOCK -> {
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
