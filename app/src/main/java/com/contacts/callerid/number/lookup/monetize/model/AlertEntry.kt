package com.contacts.callerid.number.lookup.monetize.model

data class AlertEntry(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val description: String,
    val dateTime: Long,
    val color: Int // Add this// optional
)

