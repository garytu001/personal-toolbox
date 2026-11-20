package com.local.personaltoolbox.accessibility.ads

import java.util.Locale

internal object AdSkipLabels {
    private val knownIds = setOf("skip_ad_button", "skip_ad_button_text", "ad_skip_button", "video_ad_skip_button")
    private val explicitLabels = setOf("skip ad", "skip ads", "略過廣告", "略过广告", "跳過廣告", "跳过广告")
    private val countdown = Regex("\\b(?:in|after)\\s*\\d|\\d\\s*(?:秒|seconds?|s\\b)|秒[後后]|稍[後后]|請稍候|请稍候|remaining", RegexOption.IGNORE_CASE)
    private val upsell = Regex("premium|ad[ -]?free|免費試用|免费试用|免廣告|免广告|訂閱|订阅", RegexOption.IGNORE_CASE)

    fun normalized(label: String) = label.lowercase(Locale.ROOT).trim()
        .replace(Regex("[›»▶▷→]+$"), "").trim().replace(Regex("\\s+"), " ")

    fun trustedId(id: String) = id in knownIds

    fun ready(id: String, labels: List<String>, enabled: Boolean, visible: Boolean, clickable: Boolean, buttonLike: Boolean): Boolean {
        if (!enabled || !visible || !clickable) return false
        val values = labels.map(::normalized).filter { it.isNotEmpty() }
        if (values.any { countdown.containsMatchIn(it) || upsell.containsMatchIn(it) || it.all(Char::isDigit) }) return false
        // Unqualified "Skip"/"略過" labels are accepted only with a known skip-ad resource ID.
        return trustedId(id) || (buttonLike && values.any { it in explicitLabels })
    }

    fun explicit(label: String) = normalized(label) in explicitLabels
}
