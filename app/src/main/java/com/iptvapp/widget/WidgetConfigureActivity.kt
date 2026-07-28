package com.iptvapp.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.room.Room
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ActivityWidgetConfigureBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Not @AndroidEntryPoint — matches WidgetChannelService's existing pattern of building its own
// short-lived IptvDatabase instance rather than going through Hilt, since a widget-adjacent
// component only needs a handful of one-shot reads and doesn't participate in the app's normal
// injection graph.
class WidgetConfigureActivity : Activity() {

    private lateinit var binding: ActivityWidgetConfigureBinding
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var allFavorites: List<ChannelEntity> = emptyList()
    // Plain Activity (not AppCompatActivity/ComponentActivity), so there's no built-in
    // lifecycleScope — this is cancelled explicitly in onDestroy instead.
    private val activityScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Required by the widget-host contract — if the user backs out without tapping Save,
        // the widget must not be added at all, only cancelling if this stays RESULT_CANCELED.
        setResult(Activity.RESULT_CANCELED)

        binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        loadFavorites()
        binding.btnConfigSave.setOnClickListener { saveAndFinish() }
    }

    private fun loadFavorites() {
        activityScope.launch {
            val favorites = withContext(Dispatchers.IO) {
                val db = Room.databaseBuilder(applicationContext, IptvDatabase::class.java, IptvDatabase.DATABASE_NAME)
                    .addMigrations(*IptvDatabase.ALL_MIGRATIONS)
                    .build()
                val list = db.channelDao().getFavoriteChannelsBlocking()
                db.close()
                list
            }
            allFavorites = favorites
            if (favorites.isEmpty()) {
                binding.tvConfigEmpty.visibility = android.view.View.VISIBLE
                binding.lvConfigChannels.visibility = android.view.View.GONE
                binding.btnConfigSave.visibility = android.view.View.GONE
                return@launch
            }

            val alreadySelected = WidgetPrefs.getSelectedStreamIds(this@WidgetConfigureActivity, widgetId).orEmpty()
            val adapter = ArrayAdapter(
                this@WidgetConfigureActivity,
                android.R.layout.simple_list_item_multiple_choice,
                favorites.map { it.name }
            )
            binding.lvConfigChannels.adapter = adapter
            binding.lvConfigChannels.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            favorites.forEachIndexed { index, ch ->
                if (ch.streamId in alreadySelected) binding.lvConfigChannels.setItemChecked(index, true)
            }
        }
    }

    private fun saveAndFinish() {
        val checked = binding.lvConfigChannels.checkedItemPositions
        val selected = (0 until allFavorites.size)
            .filter { checked.get(it) }
            .map { allFavorites[it].streamId }
            .toSet()

        if (selected.size > 10) {
            Toast.makeText(this, "Pick at most 10 channels", Toast.LENGTH_SHORT).show()
            return
        }

        if (selected.isEmpty()) {
            WidgetPrefs.clear(this, widgetId)
        } else {
            WidgetPrefs.setSelectedStreamIds(this, widgetId, selected)
        }

        IptvWidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), widgetId)

        val resultValue = android.content.Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
