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
