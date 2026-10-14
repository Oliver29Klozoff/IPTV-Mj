package com.iptvapp.util

/** The "English Movies & Series Only" setting's test of a provider category name. */
object CategoryLanguage {
    private val separators = Regex("[|\\-\\s:]+")

    fun isEnglish(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.trim().uppercase().split(separators).any {
            it == "EN" || it == "ENG" || it == "ENGLISH" || it == "US" || it == "USA"
        }
    }
}
