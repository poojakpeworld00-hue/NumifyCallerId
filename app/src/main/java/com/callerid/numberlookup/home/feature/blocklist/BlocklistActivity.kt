package com.callerid.numberlookup.home.feature.blocklist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityBlocklistHostBinding
import com.callerid.numberlookup.home.foundation.BaseActivity

/**
 * Standalone home for the blocklist.
 *
 * It was a nav-less tab in [com.callerid.numberlookup.home.feature.MainShellActivity],
 * reached by relaunching the shell with an extra and CLEAR_TOP. That finished
 * whatever the user had come from — Settings, every time — so Back had nothing
 * to return to and fell through to the shell's tab history, landing on Recents.
 * An Activity sits on the stack above its caller, and Back means back.
 *
 * All the behaviour stays in [BlockedNumbersFragment]; this supplies the
 * container and the bottom banner.
 */
class BlocklistActivity : BaseActivity<ActivityBlocklistHostBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "BlocklistActivity"

    override val layoutId: Int = R.layout.activity_blocklist_host

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        // Sides and bottom only: the fragment's own header takes the top inset,
        // so its title keeps clear of the status bar without this padding it
        // twice.
        ViewCompat.setOnApplyWindowInsetsListener(binding.blocklistHostRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        // Added once. On a configuration change the fragment manager restores the
        // instance it already has, and adding a second would stack two blocklists.
        if (supportFragmentManager.findFragmentById(R.id.blocklistContainer) == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.blocklistContainer, BlockedNumbersFragment())
                .commit()
        }
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, BlocklistActivity::class.java)
    }
}
