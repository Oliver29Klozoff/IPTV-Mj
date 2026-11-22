package com.iptvapp.ui.profile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptvapp.R
import com.iptvapp.data.home.ViewingPersona
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.profile.Profile
import com.iptvapp.data.profile.ProfileStore
import com.iptvapp.data.profile.ProfileSwitcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Who's watching?" and the profile editor, for the phone and TV homes. No PINs: anyone can
 * switch. From a Kids profile only switching is offered (adding and changing profiles is hidden).
 */
object ProfileUi {

    /** Set on the home screen a switch restarts, so it doesn't ask "Who's watching?" again. */
    const val EXTRA_SWITCHED = "profile_switched"

    // "Who's watching?" at start is asked once per app run, not on every Home.
    @Volatile private var askedThisRun = false

    /** The round avatar: the profile's glyph on its color, with a white ring while focused. */
    fun bindAvatar(view: TextView, profile: Profile) {
        val d = view.resources.displayMetrics.density
        fun oval(ring: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(profile.color)
            if (ring) setStroke((3 * d).toInt(), Color.WHITE)
        }
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), oval(true))
            addState(intArrayOf(android.R.attr.state_pressed), oval(true))
            addState(intArrayOf(), oval(false))
        }
        view.backgroundTintList = null
        view.text = profile.avatar.glyph
        view.contentDescription = view.context.getString(R.string.profile_switch_title) + " " + profile.name
    }

    /**
     * Home's start: "Who's watching?" when the household asked for it (two or more profiles), once
     * per run, and not on a Home a switch just restarted.
     */
    fun askAtStartIfWanted(activity: AppCompatActivity, prefs: PreferencesManager, switcher: ProfileSwitcher) {
        if (askedThisRun || activity.intent.getBooleanExtra(EXTRA_SWITCHED, false)) return
        askedThisRun = true
        activity.lifecycleScope.launch {
            val count = ProfileStore.profilesSaved(prefs).size
            if (ProfileStore.shouldAskOnStart(prefs.askProfileOnStart.first(), count)) showSwitcher(activity, prefs, switcher)
        }
    }

    fun showSwitcher(activity: AppCompatActivity, prefs: PreferencesManager, switcher: ProfileSwitcher) {
        activity.lifecycleScope.launch {
            val profiles = ProfileStore.profilesSaved(prefs)
            val activeId = prefs.activeProfileId.first()
            val kids = profiles.firstOrNull { it.id == activeId }?.isKids == true
            val askSetting = prefs.askProfileOnStart.first()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            val d = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), 0)
            }
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            lateinit var dialog: AlertDialog
            var first: View? = null
            for (p in profiles) {
                val card = card(activity, p, active = p.id == activeId) {
                    dialog.dismiss()
                    if (p.id != activeId) switchTo(activity, prefs, switcher, p)
                }
                if (!kids) card.setOnLongClickListener {
                    dialog.dismiss()
                    showEditor(activity, prefs, switcher, p)
                    true
                }
                row.addView(card)
                if (p.id == activeId) first = card
            }
            if (!kids && profiles.size < Profile.MAX_PROFILES) {
                row.addView(addCard(activity) {
                    dialog.dismiss()
                    showEditor(activity, prefs, switcher, null)
                })
            }
            if (!kids && profiles.size >= 2) {
                root.addView(CheckBox(activity).apply {
                    text = activity.getString(R.string.profile_ask_on_start)
                    isChecked = ProfileStore.shouldAskOnStart(askSetting, profiles.size)
                    setTextColor(ContextCompat.getColor(activity, R.color.rack_text))
                    setOnCheckedChangeListener { _, on -> activity.lifecycleScope.launch { prefs.setAskProfileOnStart(on) } }
                })
            }
            if (kids) {
                root.addView(TextView(activity).apply {
                    text = activity.getString(R.string.profile_kids_manage_hidden)
                    setTextColor(ContextCompat.getColor(activity, R.color.rack_text_muted))
                    setPadding(0, dp(8), 0, 0)
                })
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle(R.string.profile_switch_title)
                .setView(root)
                .setNegativeButton(R.string.profile_close, null)
            if (!kids) builder.setNeutralButton(R.string.profile_manage) { _, _ -> showManage(activity, prefs, switcher) }
            dialog = builder.create()
            dialog.setOnShowListener { first?.requestFocus() }
            dialog.show()
        }
    }

    private fun card(activity: Activity, p: Profile, active: Boolean, onClick: () -> Unit): View {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumWidth = dp(96)
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(activity, R.drawable.home_tab_bg)
            setOnClickListener { onClick() }
            addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                gravity = Gravity.CENTER
                textSize = 30f
                isDuplicateParentStateEnabled = true
                bindAvatar(this, p)
            })
            addView(TextView(activity).apply {
                text = p.name
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
                setTextColor(ContextCompat.getColor(activity, R.color.rack_text))
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            })
            val tag = listOfNotNull(
                activity.getString(R.string.profile_watching_now).takeIf { active },
                activity.getString(R.string.profile_kids_tag).takeIf { p.isKids },
            ).joinToString(" · ")
            if (tag.isNotEmpty()) addView(TextView(activity).apply {
                text = tag
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(ContextCompat.getColor(activity, R.color.oled_cyan_primary))
            })
            contentDescription = listOf(p.name, tag).filter { it.isNotEmpty() }.joinToString(", ")
        }
    }

    private fun addCard(activity: Activity, onClick: () -> Unit): View {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumWidth = dp(96)
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(activity, R.drawable.home_tab_bg)
            setOnClickListener { onClick() }
            addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                gravity = Gravity.CENTER
                text = "+"
                textSize = 32f
                setTextColor(ContextCompat.getColor(activity, R.color.rack_text))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), ContextCompat.getColor(activity, R.color.rack_text_muted))
                }
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.profile_add)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                setTextColor(ContextCompat.getColor(activity, R.color.rack_text))
            })
            contentDescription = activity.getString(R.string.profile_add)
        }
    }

    /** Switches, then starts this Home again so every screen reads the new profile's data. */
    fun switchTo(activity: AppCompatActivity, prefs: PreferencesManager, switcher: ProfileSwitcher, p: Profile) {
        val wait = AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.profile_switching, p.name))
            .setCancelable(false)
            .show()
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val ok = try {
                switcher.switchTo(p.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            if (!ok) {
                runCatching { wait.dismiss() }
                Toast.makeText(app, R.string.profile_switch_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Screens are built from the in-memory copy of the settings: let it catch up first.
            withTimeoutOrNull(1_500) { while (prefs.activeProfileIdNow() != p.id) delay(16) }
            Toast.makeText(app, app.getString(R.string.profile_switched, p.name), Toast.LENGTH_SHORT).show()
            runCatching { wait.dismiss() }
            activity.startActivity(
                Intent(activity, activity::class.java)
                    .putExtra(EXTRA_SWITCHED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            activity.finish()
        }
    }

    private fun showManage(activity: AppCompatActivity, prefs: PreferencesManager, switcher: ProfileSwitcher) {
        activity.lifecycleScope.launch {
            val profiles = ProfileStore.profilesSaved(prefs)
            val labels = profiles.map { p ->
                "${p.avatar.glyph}  ${p.name}" + if (p.isKids) " · " + activity.getString(R.string.profile_kids_tag) else ""
            }.toMutableList()
            val canAdd = profiles.size < Profile.MAX_PROFILES
            if (canAdd) labels += "+  " + activity.getString(R.string.profile_add)
            AlertDialog.Builder(activity)
                .setTitle(R.string.profile_manage)
                .setItems(labels.toTypedArray()) { _, i ->
                    showEditor(activity, prefs, switcher, profiles.getOrNull(i))
                }
                .setNegativeButton(R.string.profile_close, null)
                .show()
        }
    }

    /** Adds a profile ([existing] null) or changes one; Delete for any but Primary. */
    fun showEditor(activity: AppCompatActivity, prefs: PreferencesManager, switcher: ProfileSwitcher, existing: Profile?) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val textColor = ContextCompat.getColor(activity, R.color.rack_text)
        val mutedColor = ContextCompat.getColor(activity, R.color.rack_text_muted)
        var avatar = existing?.avatar ?: Profile.Avatar.USER
        var color = existing?.color ?: Profile.COLORS[1]

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(res: Int) = TextView(activity).apply {
            text = activity.getString(res)
            setTextColor(mutedColor)
            setPadding(0, dp(12), 0, dp(4))
        }
        val name = EditText(activity).apply {
            hint = activity.getString(R.string.profile_name_hint)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(Profile.MAX_NAME_LENGTH))
            setText(existing?.name ?: "")
            setTextColor(textColor)
        }
        form.addView(name)

        form.addView(label(R.string.profile_avatar_label))
        val avatarRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val avatarViews = mutableListOf<TextView>()
        fun refreshAvatars() {
            avatarViews.forEachIndexed { i, v ->
                val a = Profile.Avatar.entries[i]
                bindAvatar(v, Profile(name = "x", avatar = a, color = if (a == avatar) color else Color.DKGRAY))
                v.contentDescription = activity.getString(a.labelRes)
                v.isSelected = a == avatar
            }
        }
        Profile.Avatar.entries.forEach { a ->
            val v = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
                gravity = Gravity.CENTER
                textSize = 20f
                isFocusable = true
                isClickable = true
                setOnClickListener { avatar = a; refreshAvatars() }
            }
            avatarViews += v
            avatarRow.addView(v)
        }
        form.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(avatarRow) })

        form.addView(label(R.string.profile_color_label))
        val colorRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val colorViews = mutableListOf<View>()
        fun refreshColors() {
            colorViews.forEachIndexed { i, v ->
                val c = Profile.COLORS[i]
                fun oval(ring: Boolean) = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (ring) setStroke(dp(3), Color.WHITE)
                }
                v.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), oval(true))
                    addState(intArrayOf(), oval(c == color))
                }
            }
            refreshAvatars()
        }
        Profile.COLORS.forEach { c ->
            val v = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }
                isFocusable = true
                isClickable = true
                setOnClickListener { color = c; refreshColors() }
            }
            colorViews += v
            colorRow.addView(v)
        }
        form.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(colorRow) })
        refreshColors()

        val kidsSwitch = SwitchCompat(activity).apply {
            text = activity.getString(R.string.profile_kids_title)
            setTextColor(textColor)
            isChecked = existing?.isKids == true
            setPadding(0, dp(12), 0, 0)
        }
        if (existing?.isPrimary == true) {
            form.addView(TextView(activity).apply {
                text = activity.getString(R.string.profile_primary_note)
                setTextColor(mutedColor)
                setPadding(0, dp(12), 0, 0)
            })
        } else {
            form.addView(kidsSwitch)
            form.addView(TextView(activity).apply {
                text = activity.getString(R.string.profile_kids_desc)
                setTextColor(mutedColor)
                textSize = 12f
            })
        }

        // A new profile can start from a viewing style; afterwards Customize Home changes it.
        var personaGroup: RadioGroup? = null
        if (existing == null) {
            form.addView(label(R.string.profile_persona_label))
            val group = RadioGroup(activity)
            val options = listOf(null) + listOf(ViewingPersona.CLASSIC_CABLE, ViewingPersona.CINEMA_HUB, ViewingPersona.SPORTS_BAR)
            options.forEachIndexed { i, persona ->
                group.addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    tag = persona?.id
                    text = persona?.let { "${it.badge}  ${activity.getString(it.titleRes)}" } ?: activity.getString(R.string.profile_persona_keep)
                    setTextColor(textColor)
                    isChecked = i == 0
                })
            }
            form.addView(group)
            personaGroup = group
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(existing?.let { activity.getString(R.string.profile_edit_title, it.name) } ?: activity.getString(R.string.profile_new_title))
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton(R.string.profile_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null && !existing.isPrimary) builder.setNeutralButton(R.string.profile_delete, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val persona = personaGroup?.let { g -> g.findViewById<RadioButton>(g.checkedRadioButtonId)?.tag as? String }
                val profile = (existing ?: Profile(name = "")).copy(
                    name = name.text.toString(), avatar = avatar, color = color,
                    isKids = existing?.isPrimary != true && kidsSwitch.isChecked,
                    startPersonaId = existing?.startPersonaId ?: persona,
                )
                activity.lifecycleScope.launch {
                    when (val r = ProfileStore.save(prefs, profile)) {
                        is ProfileStore.SaveResult.NoName -> Toast.makeText(activity, R.string.profile_name_needed, Toast.LENGTH_SHORT).show()
                        is ProfileStore.SaveResult.Full -> Toast.makeText(activity, R.string.profile_full, Toast.LENGTH_SHORT).show()
                        is ProfileStore.SaveResult.Saved -> {
                            dialog.dismiss()
                            // The active profile's Kids flag is read by every screen: a change
                            // takes effect by starting Home again, as a switch does.
                            val activeId = prefs.activeProfileId.first()
                            if (r.profile.id == activeId && existing?.isKids != r.profile.isKids) {
                                prefs.setActiveProfileKids(r.profile.isKids)
                                restartHome(activity)
                            } else {
                                showSwitcher(activity, prefs, switcher)
                            }
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val p = existing ?: return@setOnClickListener
                activity.lifecycleScope.launch {
                    if (p.id == prefs.activeProfileId.first()) {
                        Toast.makeText(activity, R.string.profile_active_cant_delete, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.profile_delete_confirm_title, p.name))
                        .setMessage(R.string.profile_delete_confirm_msg)
                        .setPositiveButton(R.string.profile_delete) { _, _ ->
                            activity.lifecycleScope.launch {
                                if (ProfileStore.remove(prefs, p.id)) {
                                    switcher.deleteData(p.id)
                                    Toast.makeText(activity, activity.getString(R.string.profile_deleted, p.name), Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                } else {
                                    Toast.makeText(activity, R.string.profile_active_cant_delete, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            name.requestFocus()
        }
        dialog.show()
    }

    private fun restartHome(activity: AppCompatActivity) {
        activity.startActivity(
            Intent(activity, activity::class.java)
                .putExtra(EXTRA_SWITCHED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

}
