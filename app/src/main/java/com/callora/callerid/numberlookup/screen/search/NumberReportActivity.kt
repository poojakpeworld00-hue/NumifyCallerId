package com.callora.callerid.numberlookup.screen.search

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.callora.callerid.numberlookup.R
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.runtime.RewardedAdLoader
import com.callora.callerid.numberlookup.core.CoreActivity
import com.callora.callerid.numberlookup.store.BlockRegistry
import com.callora.callerid.numberlookup.databinding.ScreenLookupDetailBinding
import com.callora.callerid.numberlookup.databinding.CellNicknameBinding
import com.callora.callerid.numberlookup.screen.shared.CallPresenter

/** Full detail of a looked-up number, opened from the Lookup result card. */
class NumberReportActivity : CoreActivity<ScreenLookupDetailBinding>() {

    override val layoutId: Int = R.layout.screen_lookup_detail

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }
    private val rawNumber by lazy { intent.getStringExtra(EXTRA_RAW).orEmpty().ifBlank { number } }
    private val name by lazy { intent.getStringExtra(EXTRA_NAME) }
    private var nicknameList: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lookupDetailRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }
        RewardedAdLoader.preload(this) // ready for the "Also known as" unlock

        val displayName = name?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_caller)
        binding.lblAvatar.text = CallPresenter.initials(name, number)
        binding.lblName.text = displayName
        binding.lblNumber.text = number

        val country = intent.getStringExtra(EXTRA_COUNTRY)
        binding.lblCountry.text = textOrDash(country)
        binding.lblCarrier.text = textOrDash(intent.getStringExtra(EXTRA_CARRIER))
        binding.lblLineType.text = textOrDash(intent.getStringExtra(EXTRA_LINE_TYPE))
        // Never echo the country as the city — show a real city or "—".
        val city = intent.getStringExtra(EXTRA_CITY)?.takeIf { !it.equals(country, ignoreCase = true) }
        binding.lblCity.text = textOrDash(city)

        bindStatus()

        val spamType = intent.getStringExtra(EXTRA_SPAM_TYPE)
        if (!spamType.isNullOrBlank()) {
            binding.spamRowVw.visibility = View.VISIBLE
            binding.lblSpamType.text = spamType
        }

        showNicknames(intent.getStringArrayListExtra(EXTRA_NICKNAMES))

        binding.padCall.setOnClickListener { placeCall(rawNumber) }
        binding.padMessage.setOnClickListener { message() }
        binding.padShare.setOnClickListener { share(displayName) }
        binding.padBlock.setOnClickListener { toggleBlock() }
        updateBlockState()
    }

    private fun bindStatus() {
        val isSpam = intent.getBooleanExtra(EXTRA_IS_SPAM, false)
        val valid = intent.getIntExtra(EXTRA_VALID, -1) // 1 = valid, 0 = invalid, -1 = unknown
        val inContacts = intent.getBooleanExtra(EXTRA_IN_CONTACTS, false)

        val (textRes, fg, bg, icon) = when {
            isSpam -> Quad(R.string.lookup_spam_risk, R.color.danger, R.color.danger_soft, R.drawable.glyph_warning)
            valid == 1 -> Quad(R.string.lookup_valid_number, R.color.success, R.color.success_soft, R.drawable.glyph_verified)
            valid == 0 -> Quad(R.string.lookup_invalid_number, R.color.danger, R.color.danger_soft, R.drawable.glyph_warning)
            else -> Quad(
                if (inContacts) R.string.lookup_in_contacts else R.string.lookup_not_in_contacts,
                R.color.on_surface_variant, R.color.neutral_soft, R.drawable.glyph_info
            )
        }
        val color = ContextCompat.getColor(this, fg)
        binding.lblStatus.apply {
            setText(textRes)
            setTextColor(color)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@NumberReportActivity, bg))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(color))
        }
    }

    private fun message() {
        if (rawNumber.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$rawNumber"))) }
    }

    private fun share(displayName: String) {
        val details = buildString {
            append(displayName).append("\n").append(number)
            intent.getStringExtra(EXTRA_COUNTRY)?.takeIf { it.isNotBlank() }
                ?.let { append("\n").append(getString(R.string.lookup_country)).append(": ").append(it) }
            intent.getStringExtra(EXTRA_CARRIER)?.takeIf { it.isNotBlank() }
                ?.let { append("\n").append(getString(R.string.lookup_carrier)).append(": ").append(it) }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, details)
            }
            startActivity(Intent.createChooser(intent, null))
        }
    }

    /** Blocks/unblocks the number and refreshes the button. */
    private fun toggleBlock() {
        if (rawNumber.isBlank()) return
        val mgr = BlockRegistry(this)
        val msgRes = if (mgr.isBlocked(rawNumber)) {
            mgr.remove(rawNumber); R.string.blocklist_removed
        } else {
            mgr.add(rawNumber); R.string.blocklist_added
        }
        updateBlockState()
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    /** Reflects the current block state on the block action (label + colors). */
    private fun updateBlockState() {
        val blocked = rawNumber.isNotBlank() && BlockRegistry(this).isBlocked(rawNumber)
        val labelRes = if (blocked) R.string.action_unblock else R.string.action_block
        val fg = if (blocked) R.color.success else R.color.danger
        val soft = if (blocked) R.color.success_soft else R.color.danger_soft

        val color = ContextCompat.getColor(this, fg)
        binding.lblBlockLabel.setText(labelRes)
        binding.lblBlockLabel.setTextColor(color)
        binding.picBlockIcon.imageTintList = ColorStateList.valueOf(color)
        binding.picBlockIcon.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, soft))
    }

    // Per-number cache of already-revealed names, so a reveal stays unlocked forever.
    private val revealPrefs by lazy { getSharedPreferences("nickname_reveals", MODE_PRIVATE) }
    private fun revealKey() = "reveal_" + rawNumber.filter { it.isDigit() }.takeLast(10)
    private fun revealedSet(): Set<String> = revealPrefs.getStringSet(revealKey(), emptySet()).orEmpty()
    private fun markRevealed(nick: String) {
        revealPrefs.edit().putStringSet(revealKey(), HashSet(revealedSet()).apply { add(nick) }).apply()
    }

    /**
     * "Also known as" — each name is a locked row with its own Reveal pill.
     * One rewarded ad reveals one name; revealed names are cached and shown with
     * a green check. Names already revealed on a prior visit come back unlocked.
     */
    private fun showNicknames(nicknames: List<String>?) {
        nicknameList = nicknames.orEmpty()
        binding.nicknamesSectionVw.visibility = if (nicknameList.isEmpty()) View.GONE else View.VISIBLE
        if (nicknameList.isEmpty()) return
        renderNicknames()
    }

    private fun renderNicknames() {
        val revealed = revealedSet()
        binding.rowNicknames.removeAllViews()
        for (nick in nicknameList) {
            val row = CellNicknameBinding.inflate(layoutInflater, binding.rowNicknames, false)
            if (revealed.contains(nick)) {
                row.picNickIcon.setImageResource(R.drawable.glyph_verified)
                row.picNickIcon.imageTintList = ColorStateList.valueOf(color(R.color.success))
                row.lblNickName.text = nick
                row.lblNickName.setTextColor(color(R.color.on_surface))
                row.lblNickRevealed.visibility = View.VISIBLE
                row.padNickReveal.visibility = View.GONE
            } else {
                row.picNickIcon.setImageResource(R.drawable.glyph_lock)
                row.picNickIcon.imageTintList = ColorStateList.valueOf(color(R.color.on_surface_variant))
                row.lblNickName.text = blurName(nick)
                row.lblNickName.setTextColor(color(R.color.on_surface_variant))
                row.lblNickRevealed.visibility = View.GONE
                row.padNickReveal.visibility = View.VISIBLE
                row.padNickReveal.setOnClickListener { revealOne(nick) }
            }
            binding.rowNicknames.addView(row.root)
        }
        updateNickCount()
    }

    private fun updateNickCount() {
        val total = nicknameList.size
        val count = nicknameList.count { revealedSet().contains(it) }
        binding.lblNickCount.text = if (count == 0)
            resources.getQuantityString(R.plurals.lookup_more_names, total, total)
        else getString(R.string.lookup_x_of_n_revealed, count, total)
    }

    /** One rewarded ad reveals just this name (goes straight through if ads are off). */
    private fun revealOne(nick: String) {
        val doReveal = { if (!isFinishing) { markRevealed(nick); renderNicknames() } }
        if (AdsPrefStore.getInstance(this).getBoolean("IsAdsON")) {
            RewardedAdLoader().show(this) { doReveal() }
        } else doReveal()
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    /** First letter + dots (e.g. "John" → "J•••"). */
    private fun blurName(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    private fun textOrDash(value: String?) =
        value?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_value)

    private data class Quad(val text: Int, val fg: Int, val bg: Int, val icon: Int)

    companion object {
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_RAW = "extra_raw"
        private const val EXTRA_COUNTRY = "extra_country"
        private const val EXTRA_CITY = "extra_city"
        private const val EXTRA_CARRIER = "extra_carrier"
        private const val EXTRA_LINE_TYPE = "extra_line_type"
        private const val EXTRA_VALID = "extra_valid"
        private const val EXTRA_IS_SPAM = "extra_is_spam"
        private const val EXTRA_SPAM_TYPE = "extra_spam_type"
        private const val EXTRA_IN_CONTACTS = "extra_in_contacts"
        private const val EXTRA_NICKNAMES = "extra_nicknames"

        fun newIntent(context: Context, r: NumberVerdict): Intent =
            Intent(context, NumberReportActivity::class.java).apply {
                putExtra(EXTRA_NAME, r.name)
                putExtra(EXTRA_NUMBER, r.number)
                putExtra(EXTRA_RAW, r.rawNumber)
                putExtra(EXTRA_COUNTRY, r.country)
                putExtra(EXTRA_CITY, r.city)
                putExtra(EXTRA_CARRIER, r.carrier)
                putExtra(EXTRA_LINE_TYPE, r.lineType)
                putExtra(EXTRA_VALID, when (r.valid) { true -> 1; false -> 0; null -> -1 })
                putExtra(EXTRA_IS_SPAM, r.isSpam)
                putExtra(EXTRA_SPAM_TYPE, r.spamType)
                putExtra(EXTRA_IN_CONTACTS, r.inContacts)
                putStringArrayListExtra(EXTRA_NICKNAMES, ArrayList(r.nicknames))
            }
    }
}
