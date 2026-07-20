package com.iptvapp.ui.settings

sealed class TvSettingItem {
    data class Header(val title: String) : TvSettingItem()
    data class Toggle(
        val id: String,
        val title: String,
        val subtitle: String = "",
        var checked: Boolean,
        val valueOn: String = "ON",
        val valueOff: String = "OFF",
        // Optional small secondary action button shown to the right of the ON/OFF value (e.g.
        // "↻ Refresh Movies" next to the Show Movies Tab toggle) — independently focusable/
        // clickable from the row's own toggle-tap, since it's a distinct action, not part of
        // turning the toggle on/off.
        var actionLabel: String? = null,
        var actionEnabled: Boolean = true,
        val onAction: (() -> Unit)? = null,
        val onToggle: (Boolean) -> Unit
    ) : TvSettingItem()
    data class Action(
        val id: String,
        var title: String,
        var value: String = "",
        var enabled: Boolean = true,
        val danger: Boolean = false,
        val onClick: () -> Unit
    ) : TvSettingItem()
    data class Info(
        val id: String,
        var text: String
    ) : TvSettingItem()
    data class SubHeader(
        val id: String,
        val title: String,
        var expanded: Boolean = true,
        val onToggle: () -> Unit
    ) : TvSettingItem()
}
