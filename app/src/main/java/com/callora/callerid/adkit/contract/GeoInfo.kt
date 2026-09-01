package com.callora.callerid.adkit.contract

data class GeoInfo(
    val country: String?,
    val countryCode: String?,   // ISO 3166-1 alpha-2, e.g. "IN"
    val regionName: String?,
    val city: String?
)