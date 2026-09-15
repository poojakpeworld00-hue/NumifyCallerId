package com.contacts.callerid.number.lookup.feature.finder

/** A selectable country with its ISO-2 code and international dialing code. */
data class CountryItem(
    val name: String,
    val iso2: String,
    val dial: String
)

object CountryCatalog {

    /** Emoji flag for an ISO-2 country code (regional indicator symbols). */
    fun flag(iso2: String): String {
        if (iso2.length != 2) return "🌐"
        val base = 0x1F1E6
        val a = base + (iso2[0].uppercaseChar() - 'A')
        val b = base + (iso2[1].uppercaseChar() - 'A')
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }

    fun byIso(iso2: String): CountryItem? =
        all.firstOrNull { it.iso2.equals(iso2, ignoreCase = true) }

    val all: List<CountryItem> = listOf(
        CountryItem("United States", "US", "1"),
        CountryItem("United Kingdom", "GB", "44"),
        CountryItem("India", "IN", "91"),
        CountryItem("Canada", "CA", "1"),
        CountryItem("Australia", "AU", "61"),
        CountryItem("Germany", "DE", "49"),
        CountryItem("France", "FR", "33"),
        CountryItem("Spain", "ES", "34"),
        CountryItem("Italy", "IT", "39"),
        CountryItem("Brazil", "BR", "55"),
        CountryItem("Mexico", "MX", "52"),
        CountryItem("Japan", "JP", "81"),
        CountryItem("China", "CN", "86"),
        CountryItem("Russia", "RU", "7"),
        CountryItem("South Africa", "ZA", "27"),
        CountryItem("Nigeria", "NG", "234"),
        CountryItem("Indonesia", "ID", "62"),
        CountryItem("Philippines", "PH", "63"),
        CountryItem("Türkiye", "TR", "90"),
        CountryItem("Egypt", "EG", "20"),
        CountryItem("Pakistan", "PK", "92"),
        CountryItem("Bangladesh", "BD", "880"),
        CountryItem("United Arab Emirates", "AE", "971"),
        CountryItem("Saudi Arabia", "SA", "966"),
        CountryItem("Thailand", "TH", "66"),
        CountryItem("Vietnam", "VN", "84"),
        CountryItem("Malaysia", "MY", "60"),
        CountryItem("Singapore", "SG", "65"),
        CountryItem("Netherlands", "NL", "31"),
        CountryItem("Belgium", "BE", "32"),
        CountryItem("Switzerland", "CH", "41"),
        CountryItem("Austria", "AT", "43"),
        CountryItem("Sweden", "SE", "46"),
        CountryItem("Norway", "NO", "47"),
        CountryItem("Denmark", "DK", "45"),
        CountryItem("Finland", "FI", "358"),
        CountryItem("Poland", "PL", "48"),
        CountryItem("Portugal", "PT", "351"),
        CountryItem("Greece", "GR", "30"),
        CountryItem("Ireland", "IE", "353"),
        CountryItem("Czech Republic", "CZ", "420"),
        CountryItem("Romania", "RO", "40"),
        CountryItem("Ukraine", "UA", "380"),
        CountryItem("Hungary", "HU", "36"),
        CountryItem("Israel", "IL", "972"),
        CountryItem("South Korea", "KR", "82"),
        CountryItem("Hong Kong", "HK", "852"),
        CountryItem("Taiwan", "TW", "886"),
        CountryItem("New Zealand", "NZ", "64"),
        CountryItem("Argentina", "AR", "54"),
        CountryItem("Chile", "CL", "56"),
        CountryItem("Colombia", "CO", "57"),
        CountryItem("Peru", "PE", "51"),
        CountryItem("Kenya", "KE", "254"),
        CountryItem("Ghana", "GH", "233"),
        CountryItem("Morocco", "MA", "212"),
        CountryItem("Qatar", "QA", "974"),
        CountryItem("Kuwait", "KW", "965"),
        CountryItem("Sri Lanka", "LK", "94"),
        CountryItem("Nepal", "NP", "977")
    )

    /** International dialing code for any ISO-2 region (defaults the country chip). */
    fun dialOf(iso2: String): String? = DIAL_CODES[iso2.uppercase()]

    private val DIAL_CODES: Map<String, String> = mapOf(
        "AF" to "93", "AL" to "355", "DZ" to "213", "AD" to "376", "AO" to "244",
        "AG" to "1", "AR" to "54", "AM" to "374", "AU" to "61", "AT" to "43",
        "AZ" to "994", "BS" to "1", "BH" to "973", "BD" to "880", "BB" to "1",
        "BY" to "375", "BE" to "32", "BZ" to "501", "BJ" to "229", "BT" to "975",
        "BO" to "591", "BA" to "387", "BW" to "267", "BR" to "55", "BN" to "673",
        "BG" to "359", "BF" to "226", "BI" to "257", "KH" to "855", "CM" to "237",
        "CA" to "1", "CV" to "238", "CF" to "236", "TD" to "235", "CL" to "56",
        "CN" to "86", "CO" to "57", "KM" to "269", "CG" to "242", "CD" to "243",
        "CR" to "506", "CI" to "225", "HR" to "385", "CU" to "53", "CY" to "357",
        "CZ" to "420", "DK" to "45", "DJ" to "253", "DM" to "1", "DO" to "1",
        "EC" to "593", "EG" to "20", "SV" to "503", "GQ" to "240", "ER" to "291",
        "EE" to "372", "SZ" to "268", "ET" to "251", "FJ" to "679", "FI" to "358",
        "FR" to "33", "GA" to "241", "GM" to "220", "GE" to "995", "DE" to "49",
        "GH" to "233", "GR" to "30", "GD" to "1", "GT" to "502", "GN" to "224",
        "GW" to "245", "GY" to "592", "HT" to "509", "HN" to "504", "HK" to "852",
        "HU" to "36", "IS" to "354", "IN" to "91", "ID" to "62", "IR" to "98",
        "IQ" to "964", "IE" to "353", "IL" to "972", "IT" to "39", "JM" to "1",
        "JP" to "81", "JO" to "962", "KZ" to "7", "KE" to "254", "KI" to "686",
        "KW" to "965", "KG" to "996", "LA" to "856", "LV" to "371", "LB" to "961",
        "LS" to "266", "LR" to "231", "LY" to "218", "LI" to "423", "LT" to "370",
        "LU" to "352", "MO" to "853", "MG" to "261", "MW" to "265", "MY" to "60",
        "MV" to "960", "ML" to "223", "MT" to "356", "MH" to "692", "MR" to "222",
        "MU" to "230", "MX" to "52", "FM" to "691", "MD" to "373", "MC" to "377",
        "MN" to "976", "ME" to "382", "MA" to "212", "MZ" to "258", "MM" to "95",
        "NA" to "264", "NR" to "674", "NP" to "977", "NL" to "31", "NZ" to "64",
        "NI" to "505", "NE" to "227", "NG" to "234", "KP" to "850", "MK" to "389",
        "NO" to "47", "OM" to "968", "PK" to "92", "PW" to "680", "PS" to "970",
        "PA" to "507", "PG" to "675", "PY" to "595", "PE" to "51", "PH" to "63",
        "PL" to "48", "PT" to "351", "QA" to "974", "RO" to "40", "RU" to "7",
        "RW" to "250", "KN" to "1", "LC" to "1", "VC" to "1", "WS" to "685",
        "SM" to "378", "ST" to "239", "SA" to "966", "SN" to "221", "RS" to "381",
        "SC" to "248", "SL" to "232", "SG" to "65", "SK" to "421", "SI" to "386",
        "SB" to "677", "SO" to "252", "ZA" to "27", "KR" to "82", "SS" to "211",
        "ES" to "34", "LK" to "94", "SD" to "249", "SR" to "597", "SE" to "46",
        "CH" to "41", "SY" to "963", "TW" to "886", "TJ" to "992", "TZ" to "255",
        "TH" to "66", "TL" to "670", "TG" to "228", "TO" to "676", "TT" to "1",
        "TN" to "216", "TR" to "90", "TM" to "993", "TV" to "688", "UG" to "256",
        "UA" to "380", "AE" to "971", "GB" to "44", "US" to "1", "UY" to "598",
        "UZ" to "998", "VU" to "678", "VA" to "39", "VE" to "58", "VN" to "84",
        "YE" to "967", "ZM" to "260", "ZW" to "263", "XK" to "383"
    )
}
