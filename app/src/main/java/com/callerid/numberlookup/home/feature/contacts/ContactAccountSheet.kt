package com.callerid.numberlookup.home.feature.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isInvisible
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.ContactAccount
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The account picker behind the Contacts header chip.
 *
 * An address book is rarely one list: a phone can hold a Google account or
 * three, a SIM, and contacts saved to the device itself, and until now the tab
 * poured all of them into one directory with no way to tell which was which. The
 * sheet names each store, says how many contacts it holds, and lets one be
 * chosen — the same control the system Contacts app puts in the same place.
 *
 * It only reports a choice; [ContactListFragment] owns the filtering. The sheet
 * is handed a finished list and a selection, so it needs no repository, no view
 * model and no knowledge of how a contact is counted.
 */
class ContactAccountSheet : BottomSheetDialogFragment() {

    /** Accounts to show, "All contacts" first. Set by [show]. */
    private var accounts: List<ContactAccount> = emptyList()

    /** Currently selected account name, or null for all. */
    private var selected: String? = null

    /** Invoked with the chosen account name (null = all) as the sheet closes. */
    private var onPick: ((String?) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_contact_accounts, container, false)
        val rows = root.findViewById<LinearLayout>(R.id.containerAccounts)

        accounts.forEach { account ->
            val row = inflater.inflate(R.layout.item_contact_account, rows, false)

            val isAll = account.name == null
            row.findViewById<TextView>(R.id.textAccountTitle).text =
                account.name ?: getString(R.string.contacts_account_all)
            row.findViewById<TextView>(R.id.textAccountSub).text =
                if (isAll) getString(R.string.contacts_account_all_sub)
                else getString(R.string.contacts_account_source)
            row.findViewById<TextView>(R.id.textAccountCount).text = account.count.toString()

            // The group glyph for "All contacts", a person for one store — the
            // same pairing the header chip uses, so the two read as one control.
            row.findViewById<ImageView>(R.id.imageAccountIcon)
                .setImageResource(if (isAll) R.drawable.ic_group else R.drawable.ic_ds_person)

            // isInvisible, not GONE: the tick holds its column so the counts of
            // every row stay in one line rather than shifting under the selection.
            row.findViewById<ImageView>(R.id.iconAccountTick).isInvisible =
                account.name != selected

            row.setOnClickListener {
                onPick?.invoke(account.name)
                dismissAllowingStateLoss()
            }
            rows.addView(row)
        }
        return root
    }

    companion object {
        private const val TAG = "contact_accounts"

        /**
         * Opens the picker over [host].
         *
         * A no-op when the sheet is already up or the host cannot commit a
         * transaction, which is what stops a double tap on the header chip from
         * stacking two sheets.
         */
        fun show(
            host: androidx.fragment.app.Fragment,
            accounts: List<ContactAccount>,
            selected: String?,
            onPick: (String?) -> Unit,
        ) {
            val fm = host.childFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ContactAccountSheet().apply {
                this.accounts = accounts
                this.selected = selected
                this.onPick = onPick
            }.show(fm, TAG)
        }
    }
}
