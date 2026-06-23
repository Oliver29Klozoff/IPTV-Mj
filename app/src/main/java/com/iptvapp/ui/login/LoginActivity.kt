package com.iptvapp.ui.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivityLoginBinding
import com.iptvapp.ui.home.HomeActivity
import com.iptvapp.util.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    restoreBackup(uri)
                } catch (e: Exception) {
                    android.util.Log.e("RESTORE", "Restore failed", e)
                }
                goToHome()
            }
        } else {
            goToHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val creds = prefs.credentials.first()
            if (creds.isLoggedIn) {
                goToHome()
                return@launch
            }
            showLoginForm()
        }
    }

    private fun showLoginForm() {
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        observeLoginState()
    }

    private fun setupUI() {
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptLogin(); true } else false
        }
        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        viewModel.login(serverUrl, username, password)
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            viewModel.loginState.collect { state ->
                when (state) {
                    is Resource.Loading -> setLoading(true)
                    is Resource.Success -> {
                        setLoading(false)
                        val epgUrl = binding.etEpgUrl?.text?.toString()?.trim().orEmpty()
                        if (epgUrl.isNotEmpty()) prefs.setEpgUrl(epgUrl)
                        askRestoreAfterLogin()
                    }
                    is Resource.Error -> {
                        setLoading(false)
                        showError(state.message)
                        viewModel.resetState()
                    }
                    null -> setLoading(false)
                }
            }
        }
    }

    private fun askRestoreAfterLogin() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore backup?")
            .setMessage("Choose a backup file to restore your favorites and settings, or skip.")
            .setPositiveButton("Choose Backup") { _, _ ->
                restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
            .setNegativeButton("Skip") { _, _ -> goToHome() }
            .setCancelable(false)
            .show()
    }

    private suspend fun restoreBackup(uri: Uri) {
        val jsonText = contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: return

        val json = JSONObject(jsonText)
        android.util.Log.d("RESTORE", "Restoring from backup")

        // Credentials
        val serverUrl = json.optString("serverUrl", "")
        val username = json.optString("username", "")
        val password = json.optString("password", "")
        if (serverUrl.isNotEmpty() && username.isNotEmpty()) {
            prefs.saveCredentials(serverUrl, username, password)
        }

        // Prefs
        json.optString("epgUrl", "").takeIf { it.isNotEmpty() }?.let { prefs.setEpgUrl(it) }
        json.optString("preferredFormat", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredFormat(it) }
        if (json.has("epgAutoRefreshHours")) prefs.setEpgAutoRefreshHours(json.optInt("epgAutoRefreshHours", 0))
        if (json.has("epgRefreshMissingOnly")) prefs.setEpgRefreshMissingOnly(json.optBoolean("epgRefreshMissingOnly", false))
        if (json.has("usaOnlyChannels")) prefs.setUsaOnlyChannels(json.optBoolean("usaOnlyChannels", true))
        if (json.has("showMovies")) prefs.setShowMovies(json.optBoolean("showMovies", true))
        if (json.has("showSeries")) prefs.setShowSeries(json.optBoolean("showSeries", true))

        // Favorite categories
        val favCatArray = json.optJSONArray("favoriteCategoryIds")
        if (favCatArray != null) {
            val ids = (0 until favCatArray.length()).map { favCatArray.getString(it) }.toSet()
            android.util.Log.d("RESTORE", "Restoring ${ids.size} favorite categories")
            prefs.setFavoriteLiveCategoryIds(ids)
        }

        // Favorite channels - store for later since channels may not be in DB yet
        val favChanArray = json.optJSONArray("favoriteChannelIds")
        if (favChanArray != null) {
            val ids = (0 until favChanArray.length()).map { favChanArray.getInt(it) }
            android.util.Log.d("RESTORE", "Saving ${ids.size} favorite channel IDs for later restore")
            // Save as a pending restore list in prefs
            prefs.setPendingFavoriteChannelIds(ids.toSet())
        }

        android.util.Log.d("RESTORE", "Restore complete")
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.text = if (loading) "Connecting..." else "Connect"
    }

    private fun showError(message: String?) {
        Snackbar.make(binding.root, message ?: "Error", Snackbar.LENGTH_LONG).show()
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
