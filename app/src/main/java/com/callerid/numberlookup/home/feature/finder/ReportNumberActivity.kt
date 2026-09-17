package com.callerid.numberlookup.home.feature.finder

import com.callerid.numberlookup.home.feature.premium.PremiumActivity
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
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.delivery.RewardPrompt
import com.callerid.numberlookup.home.monetize.delivery.RewardedAdPresenter
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.feature.blocklist.BlockReward
import com.callerid.numberlookup.home.repository.BlocklistRepository
import com.callerid.numberlookup.home.databinding.ActivityLookupDetailBinding
import com.callerid.numberlookup.home.databinding.ItemNicknameBinding
import com.callerid.numberlookup.home.feature.widgets.CallActionHandler

/** Full detail of a looked-up number, opened from the Lookup result card. */
class ReportNumberActivity : BaseActivity<ActivityLookupDetailBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "ReportNumberActivity"

    override val layoutId: Int = R.layout.activity_lookup_detail

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }
    private val rawNumber by lazy { intent.getStringExtra(EXTRA_RAW).orEmpty().ifBlank { number } }
    private val name by lazy { intent.getStringExtra(EXTRA_NAME) }
    private var nicknameList: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lookupDetailRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }
        RewardedAdPresenter.preload(this) // ready for the "Also known as" unlock

        val displayName = name?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_caller)
        binding.textAvatar.text = CallActionHandler.initials(name, number)
        binding.textName.text = displayName
        binding.textNumber.text = number

        val country = intent.getStringExtra(EXTRA_COUNTRY)
        binding.textCountry.text = textOrDash(country)
        binding.textCarrier.text = textOrDash(intent.getStringExtra(EXTRA_CARRIER))
        binding.textLineType.text = textOrDash(intent.getStringExtra(EXTRA_LINE_TYPE))
        // Never echo the country as the city — show a real city or "—".
        val city = intent.getStringExtra(EXTRA_CITY)?.takeIf { !it.equals(country, ignoreCase = true) }
        binding.textCity.text = textOrDash(city)

        bindStatus()

        val spamType = intent.getStringExtra(EXTRA_SPAM_TYPE)
        if (!spamType.isNullOrBlank()) {
            binding.spamRow.visibility = View.VISIBLE
            // Its divider goes with it, or the card ends on a hairline under the
            // last visible row.
            binding.spamDivider.visibility = View.VISIBLE
            binding.textSpamType.text = spamType
        }

        showNicknames(intent.getStringArrayListExtra(EXTRA_NICKNAMES))

        binding.buttonCall.setOnClickListener { placeCall(rawNumber) }
        binding.buttonMessage.setOnClickListener { message() }
        binding.buttonShare.setOnClickListener { share(displayName) }
        binding.buttonBlock.setOnClickListener { toggleBlock() }
        updateBlockState()
    }

    /**
     * The verdict pill, which now sits on the dark hero rather than on the page.
     *
     * The label is white and the glyph carries the verdict, instead of the whole
     * pill taking a soft tint: `success_soft` and `danger_soft` are 12% washes
     * built to sit on white, and their text colours — a mid-green, a mid-red —
     * fall to about 3:1 against near-black. The colour moves to the icon, where
     * a brighter tone is legible, and the text keeps full contrast.
     */
    private fun bindStatus() {
        val isSpam = intent.getBooleanExtra(EXTRA_IS_SPAM, false)
        val valid = intent.getIntExtra(EXTRA_VALID, -1) // 1 = valid, 0 = invalid, -1 = unknown
        val inContacts = intent.getBooleanExtra(EXTRA_IN_CONTACTS, false)

        val (textRes, glyphColor, icon) = when {
            isSpam -> Triple(R.string.lookup_spam_risk, R.color.ds_on_hero_danger, R.drawable.ic_warning)
            valid == 1 -> Triple(R.string.lookup_valid_number, R.color.ds_on_hero_success, R.drawable.ic_verified)
            valid == 0 -> Triple(R.string.lookup_invalid_number, R.color.ds_on_hero_danger, R.drawable.ic_warning)
            else -> Triple(
                if (inContacts) R.string.lookup_in_contacts else R.string.lookup_not_in_contacts,
                R.color.ds_on_hero_muted, R.drawable.ic_info
            )
        }
        binding.textStatus.apply {
            setText(textRes)
            setTextColor(ContextCompat.getColor(this@ReportNumberActivity, R.color.ds_on_hero))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(
                this,
                ColorStateList.valueOf(
                    ContextCompat.getColor(this@ReportNumberActivity, glyphColor)
                )
            )
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

    /**
     * Blocks/unblocks the number and refreshes the button.
     *
     * Only the blocking half goes through the reward gate — unblocking is the
     * user giving a slot back and must never cost them an ad.
     */
    private fun toggleBlock() {
        if (rawNumber.isBlank()) return
        val mgr = BlocklistRepository(this)

        if (mgr.isNumberBlocked(rawNumber)) {
            mgr.remove(rawNumber)
            updateBlockState()
            Toast.makeText(this, R.string.blocklist_removed, Toast.LENGTH_SHORT).show()
            return
        }

        BlockReward.allow(this, rawNumber) {
            mgr.add(rawNumber)
            updateBlockState()
            Toast.makeText(this, R.string.blocklist_added, Toast.LENGTH_SHORT).show()
        }
    }

    /** Reflects the current block state on the block action (label + colors). */
    private fun updateBlockState() {
        val blocked = rawNumber.isNotBlank() && BlocklistRepository(this).isNumberBlocked(rawNumber)
        val labelRes = if (blocked) R.string.action_unblock else R.string.action_block
        val fg = if (blocked) R.color.ds_success else R.color.ds_danger
        val soft = if (blocked) R.color.ds_success_wash else R.color.ds_danger_tint

        val color = ContextCompat.getColor(this, fg)
        binding.textBlockLabel.setText(labelRes)
        // The label stays the same ink as its three neighbours; the tile carries
        // the state. Colouring one label of four made the strip read as three
        // buttons and a warning.
        binding.textBlockLabel.setTextColor(ContextCompat.getColor(this, R.color.ds_ink))
        binding.imageBlockIcon.imageTintList = ColorStateList.valueOf(color)
        binding.imageBlockIcon.backgroundTintList =
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
     * "Also known as".
     *
     * The first few names are the ones a free user may open: eye, rewarded ad,
     * name, and the reveal is remembered so a name opened last visit comes back
     * already open. How many is [NameRevealPolicy.revealableLimit], which Remote
     * Config sets.
     *
     * Past that the rows wear a lock and lead to the paywall instead of an ad.
     * Every one of these names used to be reachable for the price of watching
     * something, which is Premium's whole offer on this screen handed out one ad
     * at a time.
     */
    private fun showNicknames(nicknames: List<String>?) {
        nicknameList = nicknames.orEmpty()
        binding.nicknamesSection.visibility = if (nicknameList.isEmpty()) View.GONE else View.VISIBLE
        if (nicknameList.isEmpty()) return
        renderNicknames()
    }

    private fun renderNicknames() {
        val revealed = revealedSet()
        binding.columnNicknames.removeAllViews()
        for ((index, nick) in nicknameList.withIndex()) {
            val row = ItemNicknameBinding.inflate(layoutInflater, binding.columnNicknames, false)
            val revealable = NameRevealPolicy.isRevealable(this, index)
            if (revealed.contains(nick)) {
                row.imageNickIcon.setImageResource(R.drawable.ic_verified)
                row.imageNickIcon.imageTintList = ColorStateList.valueOf(color(R.color.success))
                row.textNickName.text = nick
                row.textNickName.setTextColor(color(R.color.on_surface))
                row.textNickRevealed.visibility = View.VISIBLE
                row.buttonNickReveal.visibility = View.GONE
            } else {
                row.imageNickIcon.setImageResource(R.drawable.ic_lock)
                row.imageNickIcon.imageTintList = ColorStateList.valueOf(color(R.color.on_surface_variant))
                row.textNickName.text = blurName(nick)
                row.textNickName.setTextColor(color(R.color.on_surface_variant))
                row.textNickRevealed.visibility = View.GONE
                row.buttonNickReveal.visibility = View.VISIBLE

                if (revealable) {
                    row.imageNickRevealIcon.setImageResource(R.drawable.ic_play)
                    row.textNickRevealLabel.setText(R.string.lookup_reveal)
                    row.buttonNickReveal.setBackgroundResource(R.drawable.bg_reveal_pill)
                    row.buttonNickReveal.setOnClickListener { revealOne(nick) }
                } else {
                    // Past the free line: the pill stops offering an ad and starts
                    // offering the thing that removes the line.
                    row.imageNickRevealIcon.setImageResource(R.drawable.ic_lock)
                    row.textNickRevealLabel.setText(R.string.lookup_reveal_premium)
                    row.buttonNickReveal.setBackgroundResource(R.drawable.bg_premium_pill)
                    row.buttonNickReveal.setOnClickListener {
                        startActivity(PremiumActivity.newIntent(this))
                    }
                }
            }
            binding.columnNicknames.addView(row.root)
        }
        updateNickCount()
    }

    private fun updateNickCount() {
        val total = nicknameList.size
        val count = nicknameList.count { revealedSet().contains(it) }
        binding.textNickCount.text = if (count == 0)
            resources.getQuantityString(R.plurals.lookup_more_names, total, total)
        else getString(R.string.lookup_x_of_n_revealed, count, total)
    }

    /**
     * One rewarded ad reveals just this name (goes straight through if ads are
     * off).
     *
     * It asks first. This was the one rewarded flow in the app that dropped the
     * user into a full-screen ad on a single tap, with no statement of what the
     * ad was for and no way back — every other one confirmed. It now shows the
     * same [RewardPrompt] dialog as the rest, with the name masked.
     */
    private fun revealOne(nick: String) {
        val doReveal = { if (!isFinishing) { markRevealed(nick); renderNicknames() } }
        RewardPrompt.show(this, RewardPrompt.nameReveal(nick, number)) { doReveal() }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    /** First letter + dots (e.g. "John" → "J•••"). */
    private fun blurName(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    private fun textOrDash(value: String?) =
        value?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_value)


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

        fun newIntent(context: Context, r: LookupVerdict): Intent =
            Intent(context, ReportNumberActivity::class.java).apply {
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
