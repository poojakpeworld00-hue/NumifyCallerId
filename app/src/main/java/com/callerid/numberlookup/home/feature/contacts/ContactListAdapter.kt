package com.callerid.numberlookup.home.feature.contacts

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.ContactGroup
import com.callerid.numberlookup.home.repository.ContactRecord
import com.callerid.numberlookup.home.common.AvatarPalette
import com.callerid.numberlookup.home.databinding.ItemContactBinding
import com.callerid.numberlookup.home.databinding.ItemContactGroupBinding
import com.callerid.numberlookup.home.databinding.ItemContactGroupHeadBinding
import com.callerid.numberlookup.home.databinding.ItemSectionHeaderBinding

class ContactListAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (ContactRecord) -> Unit,
    /** A group row was tapped (Groups tab). */
    private val onOpenGroup: (String) -> Unit = {},
    /** The open group's heading was tapped: back to the list of groups. */
    private val onCloseGroup: () -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<ContactRowUi> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ContactRowUi>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ContactRowUi.Header -> TYPE_HEADER
        is ContactRowUi.Item -> TYPE_CONTACT
        is ContactRowUi.Group -> TYPE_GROUP
        is ContactRowUi.GroupHead -> TYPE_GROUP_HEAD
    }

    /**
     * Lets the divider decoration skip the boundaries either side of a letter,
     * and under an open group's heading, which is the page's title rather than
     * one of its rows.
     */
    fun isHeader(position: Int): Boolean =
        position in rows.indices &&
            (rows[position] is ContactRowUi.Header || rows[position] is ContactRowUi.GroupHead)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_GROUP -> GroupVH(ItemContactGroupBinding.inflate(inflater, parent, false))
            TYPE_GROUP_HEAD -> GroupHeadVH(ItemContactGroupHeadBinding.inflate(inflater, parent, false))
            else -> ContactVH(ItemContactBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ContactRowUi.Header -> (holder as HeaderVH).bind(row.letter)
            is ContactRowUi.Item -> (holder as ContactVH).bind(row)
            is ContactRowUi.Group -> (holder as GroupVH).bind(row.group)
            is ContactRowUi.GroupHead -> (holder as GroupHeadVH).bind(row.group)
        }
    }

    private fun countLabel(view: View, count: Int): String =
        view.resources.getQuantityString(R.plurals.contacts_group_count, count, count)

    inner class GroupVH(private val binding: ItemContactGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(group: ContactGroup) {
            binding.textGroupName.text = group.title
            binding.textGroupCount.text = countLabel(binding.root, group.count)
            binding.root.setOnClickListener { onOpenGroup(group.title) }
        }
    }

    inner class GroupHeadVH(private val binding: ItemContactGroupHeadBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(group: ContactGroup) {
            binding.textGroupHeadName.text = group.title
            binding.textGroupHeadCount.text = countLabel(binding.root, group.count)
            binding.root.setOnClickListener { onCloseGroup() }
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(letter: String) {
            binding.textHeader.text = letter
            binding.textHeader.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.ds_ink)
            )
        }
    }

    inner class ContactVH(val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ContactRowUi.Item) {
            val c = row.contact
            binding.textAvatar.text = c.initials
            // Every avatar takes its own colour, keyed off the contact so it stays
            // the same person's colour on every launch.
            binding.textAvatar.backgroundTintList =
                AvatarPalette.tintFor(binding.root.context, c.name.ifEmpty { c.detail })
            binding.textName.text = c.name
            binding.textNumber.text = c.detail
            loadContactPhoto(c)
            // An email-only contact (search) has nothing to dial.
            binding.buttonCall.visibility = if (c.hasPhone) View.VISIBLE else View.INVISIBLE
            binding.buttonCall.setOnClickListener { onCall(c.detail) }
            binding.root.setOnClickListener { onOpen(c) }
        }

        /** Shows the real contact photo over the initials, falling back to initials. */
        private fun loadContactPhoto(c: ContactRecord) {
            val iv = binding.imageAvatar
            val uri = c.photoUri
            if (uri.isNullOrBlank()) {
                Glide.with(iv).clear(iv)
                iv.setImageDrawable(null)
                iv.visibility = View.GONE
                return
            }
            iv.visibility = View.VISIBLE
            Glide.with(iv)
                .load(Uri.parse(uri))
                .circleCrop()
                .into(iv)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1
        private const val TYPE_GROUP = 2
        private const val TYPE_GROUP_HEAD = 3
    }
}
