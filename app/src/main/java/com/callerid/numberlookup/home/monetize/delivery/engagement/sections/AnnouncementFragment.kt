package com.callerid.numberlookup.home.monetize.delivery.engagement.sections

import androidx.annotation.StringRes
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.PreferenceStore
import com.callerid.numberlookup.home.common.triggerClick

class AnnouncementFragment : Fragment() {

    /** Caller's number to text directly; null → user picks the recipient in their SMS app. */
    private val targetNumber: String? by lazy { arguments?.getString(ARG_NUMBER) }

    private lateinit var etCustomMessage: EditText
    private lateinit var btnSendMessage: ImageView
    private lateinit var radioContainer: ViewGroup
    private var selectedIndex = -1

    private val options = listOf(
        "Sorry! Can't talk right now",
        "Can I call you later?",
        "I'm on my way",
        "Call me in 5 minutes",
        "Call me in 10 minutes"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_message, container, false)

        radioContainer = view.findViewById(R.id.radioContainer)
        etCustomMessage = view.findViewById(R.id.inputCustomMessage)
        btnSendMessage = view.findViewById(R.id.buttonSendMessage)

        setupOptions(inflater)
        setupListeners()

        return view
    }

    private fun setupOptions(inflater: LayoutInflater) {
        radioContainer.removeAllViews()

        options.forEachIndexed { index, text ->
            val itemView = inflater.inflate(R.layout.item_radio_option, radioContainer, false)
            val tvOption = itemView.findViewById<TextView>(R.id.textOption)
            val imgCheck = itemView.findViewById<ImageView>(R.id.imageCheck)

            tvOption.text = text

            itemView.triggerClick {
                selectedIndex = index
                etCustomMessage.setText(text)
                refreshSelection()
            }

            radioContainer.addView(itemView)
        }
    }

    private fun refreshSelection() {
        if (!isAdded) return
        val context = context ?: return
        val currentTheme = context.getCurrentTheme()
        Log.d("ThemeCheck", "Current Theme: $currentTheme")

        for (i in 0 until radioContainer.childCount) {
            val child = radioContainer.getChildAt(i)
            val tvOption = child.findViewById<TextView>(R.id.textOption)
            val imgCheck = child.findViewById<ImageView>(R.id.imageCheck)

            if (i == selectedIndex) {
                tvOption.setTextColor(requireContext().getColor(R.color.primary))
                imgCheck.setImageResource(R.drawable.ic_radio)
            } else {
                val textColor = context.getThemeTextColor()
                Log.d("ThemeCheck", "Option $i textColor: $textColor")
                tvOption.setTextColor(textColor)
                imgCheck.setImageResource(R.drawable.bg_radio_unselected)
            }
        }
    }

    fun Context.getThemeTextColor(): Int {
        val theme = getCurrentTheme()
        Log.d("ThemeCheck", "getThemeTextColor called, theme: $theme")

        return when (theme) {
            "light" -> Color.BLACK
            "dark" -> Color.WHITE
            "system" -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == Configuration.UI_MODE_NIGHT_YES)
                    Color.WHITE
                else
                    Color.BLACK
            }

            else -> Color.BLACK
        }
    }

    fun Context.getCurrentTheme(): String {
        val theme = PreferenceStore.selectedTheme(this)
        Log.d("ThemeCheck", "getCurrentTheme: $theme")
        return theme
    }

    private fun setupListeners() {
        btnSendMessage.triggerClick {
            if (!isAdded) return@triggerClick

            val message = etCustomMessage.text.toString().trim()

            if (message.isNotBlank()) {
                sendMessage(message)
            } else {
                context?.let {
                    Toast.makeText(it, R.string.toast_pick_message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendMessage(message: String) {
        // Text the caller directly when we know their number; otherwise open the
        // SMS app with no recipient (the user picks). `smsto:` is kept either way
        // so ACTION_SENDTO resolves only to SMS apps.
        val smsUri = if (!targetNumber.isNullOrBlank()) "smsto:${targetNumber}" else "smsto:"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(smsUri)
            putExtra("sms_body", message)
        }

        safeStartActivity(intent, R.string.toast_no_sms_app)
    }

    companion object {
        private const val ARG_NUMBER = "arg_number"

        /** [number] = the caller to text directly (null/blank → recipient-less SMS). */
        fun newInstance(number: String?): AnnouncementFragment = AnnouncementFragment().apply {
            arguments = Bundle().apply { putString(ARG_NUMBER, number) }
        }
    }

    private fun safeStartActivity(intent: Intent, @StringRes errorMsg: Int) {
        val ctx = context ?: return
        if (!isAdded) return

        try {
            if (intent.resolveActivity(ctx.packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(ctx, errorMsg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }
}