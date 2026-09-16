package com.callerid.numberlookup.home.monetize.delivery.engagement.sections

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.callerid.numberlookup.home.monetize.model.AlertEntry
import com.callerid.numberlookup.home.monetize.delivery.engagement.lists.AlertFeedAdapter
import com.callerid.numberlookup.home.monetize.delivery.engagement.broadcast.AlertScheduleWorker
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.triggerClick
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AlertFeedFragment : Fragment() {
    private lateinit var reminderList: MutableList<AlertEntry>
    private lateinit var adapter: AlertFeedAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: View
    private var calendar = Calendar.getInstance()
    private lateinit var emptyLayout: View
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_reminder, container, false)

        recyclerView = view.findViewById(R.id.listReminders)
        fab = view.findViewById(R.id.fabAddReminder)
        emptyLayout = view.findViewById(R.id.emptyLayout)

        reminderList = mutableListOf()
        adapter = AlertFeedAdapter(reminderList, ::deleteReminder)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        fab.triggerClick {
            showAddReminderDialog()
        }

        requestNotificationPermissionIfNeeded()
        loadReminders() // Load saved reminders

        updateEmptyState()

        return view
    }

    private fun updateEmptyState() {
        if (reminderList.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyLayout.visibility = View.GONE
        }
    }

    private val defaultColor: Int = Color.parseColor("#FFA500")

    private fun showAddReminderDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_reminder, null)

        val etTitle = dialogView.findViewById<EditText>(R.id.edit_reminder).apply {
            hint = "e.g. Birthday, Meeting, Call back..."
            setHintTextColor(Color.parseColor("#999999"))
            setBackgroundResource(R.drawable.bg_btn)
            setPadding(32, 24, 32, 24)
            requestFocus()
        }

        // Auto-show keyboard when dialog opens
        dialogView.post {
            val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(etTitle, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        val npMonth = dialogView.findViewById<NumberPicker>(R.id.keypadMonth)
        val npDay = dialogView.findViewById<NumberPicker>(R.id.keypadDay)
        val npHour = dialogView.findViewById<NumberPicker>(R.id.keypadHour)
        val npMinute = dialogView.findViewById<NumberPicker>(R.id.keypadMinute)
        val cal = Calendar.getInstance()

        npMonth.minValue = 1
        npMonth.maxValue = 12
        npMonth.displayedValues = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        npMonth.value = cal.get(Calendar.MONTH) + 1

        npDay.minValue = 1
        npDay.maxValue = 31
        npDay.value = cal.get(Calendar.DAY_OF_MONTH)

        npHour.minValue = 0
        npHour.maxValue = 23
        npHour.value = cal.get(Calendar.HOUR_OF_DAY)

        npMinute.minValue = 0
        npMinute.maxValue = 59
        npMinute.value = cal.get(Calendar.MINUTE)


        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx).setView(dialogView).create()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setGravity(Gravity.CENTER)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnSave = dialogView.findViewById<TextView>(R.id.buttonSave)
        val btnCancel = dialogView.findViewById<TextView>(R.id.buttonCancel)

        btnSave.triggerClick {
            val title = etTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(context, R.string.toast_need_title, Toast.LENGTH_SHORT).show()
                return@triggerClick
            }

            val reminderCal = Calendar.getInstance()
            reminderCal.set(Calendar.MONTH, npMonth.value - 1)
            reminderCal.set(Calendar.DAY_OF_MONTH, npDay.value)
            reminderCal.set(Calendar.HOUR_OF_DAY, npHour.value)
            reminderCal.set(Calendar.MINUTE, npMinute.value)
            reminderCal.set(Calendar.SECOND, 0)
            reminderCal.set(Calendar.MILLISECOND, 0)

            val reminder = AlertEntry(
                title = title, description = "", dateTime = reminderCal.timeInMillis, color = defaultColor
            )
            reminderList.add(reminder)
            saveReminders()
            scheduleReminderWithWorkManager(reminder)
            adapter.notifyDataSetChanged()
            updateEmptyState()
            recyclerView.scrollToPosition(reminderList.size - 1)
            dialog.dismiss()
        }
        btnCancel.triggerClick {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun scheduleReminderWithWorkManager(reminder: AlertEntry) {
        val delay = reminder.dateTime - System.currentTimeMillis()
        if (delay <= 0) {
            Toast.makeText(context, R.string.toast_need_future_time, Toast.LENGTH_SHORT).show()
            return
        }

        val data = workDataOf(
            "title" to reminder.title, "message" to reminder.description
        )
        val request = OneTimeWorkRequestBuilder<AlertScheduleWorker>().setInitialDelay(
                delay,
                TimeUnit.MILLISECONDS
            ).setInputData(data).addTag("reminder").build()

        val ctx = context ?: return
        WorkManager.getInstance(ctx).enqueue(request)
    }

    private fun requestNotificationPermissionIfNeeded() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private val PREFS_NAME = "reminder_prefs"
    private val KEY_REMINDERS = "reminders"

    private fun saveReminders() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, 0)
        val editor = prefs.edit()
        val json = Gson().toJson(reminderList)
        editor.putString(KEY_REMINDERS, json)
        editor.apply()
    }

    private fun loadReminders() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, 0)
        val json = prefs.getString(KEY_REMINDERS, null)
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<MutableList<AlertEntry>>() {}.type
            val saved: MutableList<AlertEntry> = Gson().fromJson(json, type)
            reminderList.clear()
            reminderList.addAll(saved)
            adapter.notifyDataSetChanged()
        }
    }

    private fun deleteReminder(reminder: AlertEntry) {
        val index = reminderList.indexOf(reminder)
        if (index != -1) {
            reminderList.removeAt(index)
            adapter.notifyDataSetChanged()
            saveReminders()
            updateEmptyState()
            val ctx = context ?: return
            WorkManager.getInstance(ctx).cancelAllWorkByTag("reminder_${reminder.id}")
        }
    }
}
