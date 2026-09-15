package com.contacts.callerid.number.lookup.feature.finder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.databinding.ActivityLookupBinding
import com.contacts.callerid.number.lookup.foundation.BaseActivity

/**
 * Standalone home for the number search.
 *
 * Lookup was a nav-less tab in [com.contacts.callerid.number.lookup.feature.MainShellActivity],
 * reached from the raised centre action. That action is gone and the only way in
 * now is a tile in Tools — where everything else is an Activity — so this is one
 * too: its own entry in the back stack, its own back ad, and a Back button that
 * leaves the app's tabs where the user left them instead of swapping the pane
 * under the bar.
 *
 * All the screen's behaviour stays in [NumberFinderFragment], which pads its own
 * status-bar inset; this supplies the container and the bottom banner.
 */
class LookupActivity : BaseActivity<ActivityLookupBinding>() {

    override val layoutId: Int = R.layout.activity_lookup

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        // Only the sides and the bottom: the fragment's hero deliberately bleeds
        // under the status bar and pads the top inset onto itself.
        ViewCompat.setOnApplyWindowInsetsListener(binding.lookupRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        // Added once. On a configuration change the fragment manager restores the
        // instance it already has, and adding a second one would stack two search
        // fields on top of each other.
        if (supportFragmentManager.findFragmentById(R.id.lookupContainer) == null) {
            val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }
            supportFragmentManager.beginTransaction()
                .add(R.id.lookupContainer, NumberFinderFragment.newInstance(number))
                .commit()
        }
    }

    /**
     * A second start while this screen is already up hands the number to the
     * fragment that is here rather than building another one.
     *
     * The manifest marks this singleTop, so a double tap on the Tools tile — two
     * taps through an interstitial that takes a moment to clear — reuses this
     * instance instead of stacking a second Lookup for the user to back out of
     * twice. Without this the reused instance would simply ignore the number.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: return
        val fragment = supportFragmentManager.findFragmentById(R.id.lookupContainer)
        (fragment as? NumberFinderFragment)?.requestSearch(number)
    }

    companion object {
        /** A number to search on arrival — e.g. "Identify" from a recents row. */
        const val EXTRA_NUMBER = "extra_lookup_number"

        fun newIntent(context: Context, number: String? = null): Intent =
            Intent(context, LookupActivity::class.java).apply {
                if (!number.isNullOrBlank()) putExtra(EXTRA_NUMBER, number)
            }
    }
}
