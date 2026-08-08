package com.iptvapp.ui.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
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
    @Inject lateinit var repository: XtreamRepository

    private val m3uFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@launch
                    setLoading(true)
                    val result = repository.importM3uFromText(text)
                    setLoading(false)
                    when (result) {
                        is Resource.Success -> {
                            Snackbar.make(binding.root, "Imported ${result.data} channels", Snackbar.LENGTH_SHORT).show()
                            goToHome()
                        }
                        is Resource.Error -> showError("Import failed: ${result.message}")
                        else -> {}
                    }
                } catch (e: Exception) {
                    setLoading(false)
                    showError("Import failed: ${e.message}")
                }
            }
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        if (!applyRestorePayload(text)) {
            Snackbar.make(binding.root, "QR code not recognized", Snackbar.LENGTH_LONG).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScanner() else {
            Snackbar.make(binding.root, "Camera permission is required to scan", Snackbar.LENGTH_LONG).show()
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
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
        // Handle mktv://restore?d= QR scan from TV
        val restoreUri = intent?.data
        if (restoreUri?.scheme == "mktv" && restoreUri.host == "restore") {
            handleRestoreDeepLink(restoreUri)
            return
        }
        lifecycleScope.launch {
            val creds = prefs.credentials.first()
            if (creds.isLoggedIn) {
                goToHome()
                return@launch
            }
            showLoginForm()
        }
    }

    private fun handleRestoreDeepLink(uri: Uri) {
        showLoginForm()
        val encoded = uri.getQueryParameter("d") ?: return
        applyRestorePayload(encoded)
    }

    /** Parses a raw `mktv://restore?d=` value OR a bare base64 payload (from an in-app QR scan)
     * and fills the login form. Returns false if the text isn't a recognized MKTV backup payload. */
    private fun applyRestorePayload(raw: String): Boolean {
        val encoded = try {
            val uri = Uri.parse(raw)
            if (uri.scheme == "mktv" && uri.host == "restore") uri.getQueryParameter("d") ?: raw else raw
        } catch (_: Exception) { raw }
        val json = try {
            JSONObject(String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE)))
        } catch (_: Exception) { return false }
        val serverUrl = json.optString("s")
        val username  = json.optString("u")
        val password  = json.optString("p")
        if (serverUrl.isEmpty() || username.isEmpty()) return false
        binding.etServerUrl.setText(serverUrl)
        binding.etUsername.setText(username)
        binding.etPassword.setText(password)
        return true
    }

    private fun scanQrClicked() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchQrScanner() else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun launchQrScanner() {
        qrScanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the QR code shown on your TV")
            setBeepEnabled(false)
            setOrientationLocked(true)
        })
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
        binding.btnImportM3u.setOnClickListener { showM3uImportDialog() }
        binding.btnScanQr.setOnClickListener { scanQrClicked() }
        binding.btnRestoreFromBackup.setOnClickListener { restoreFromBackupLauncher.launch(arrayOf("*/*")) }
    }

    // Picks a backup file and logs in using ITS saved credentials directly — skips typing
    // server/username/password by hand entirely, unlike restoreLauncher below which only ever
    // runs after a manual login already succeeded. Falls back to showing an error if the file
    // has no usable credentials (an old backup predating serverUrl/username being saved, or a
    // non-MKTV file) rather than silently doing nothing.
    private val restoreFromBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val jsonText = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                if (jsonText == null) {
                    showError("Couldn't read that file")
                    return@launch
                }
                val json = JSONObject(jsonText)
                val serverUrl = json.optString("serverUrl", "")
                val username = json.optString("username", "")
                val password = json.optString("password", "")
                if (serverUrl.isEmpty() || username.isEmpty()) {
                    showError("This backup has no saved login — use Scan QR or enter credentials manually")
                    return@launch
                }
                pendingBackupRestoreUri = uri
                binding.etServerUrl.setText(serverUrl)
                binding.etUsername.setText(username)
                binding.etPassword.setText(password)
                viewModel.login(serverUrl, username, password)
            } catch (e: Exception) {
                showError("Restore failed: ${e.message}")
            }
        }
    }

    // Set right before viewModel.login() fires from restoreFromBackupLauncher so
    // observeLoginState's Resource.Success branch can finish the restore (favorites/settings/etc)
    // with the same file instead of prompting askRestoreAfterLogin's separate file picker again.
    private var pendingBackupRestoreUri: Uri? = null

    private fun showM3uImportDialog() {
        val options = arrayOf("Enter M3U URL", "Choose file")
        MaterialAlertDialogBuilder(this)
            .setTitle("Import M3U Playlist")
            .setItems(options) { _, which ->
                if (which == 0) {
                    showM3uUrlDialog()
                } else {
                    m3uFileLauncher.launch(arrayOf("*/*", "text/*", "audio/x-mpegurl"))
                }
            }
            .show()
    }

    private fun showM3uUrlDialog() {
        val input = EditText(this).apply {
            hint = "http://example.com/playlist.m3u"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("M3U URL")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    lifecycleScope.launch {
                        setLoading(true)
                        val result = repository.importM3uFromUrl(url)
                        setLoading(false)
                        when (result) {
                            is Resource.Success -> {
                                Snackbar.make(binding.root, "Imported ${result.data} channels", Snackbar.LENGTH_SHORT).show()
                                goToHome()
                            }
                            is Resource.Error -> showError("Import failed: ${result.message}")
                            else -> {}
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attemptLogin() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val nickname = binding.etNickname.text.toString().trim()
        lifecycleScope.launch { prefs.setServerNickname(nickname) }
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
                        val backupUri = pendingBackupRestoreUri
                        if (backupUri != null) {
                            pendingBackupRestoreUri = null
                            lifecycleScope.launch {
                                try { restoreBackup(backupUri) } catch (e: Exception) {
                                    android.util.Log.e("RESTORE", "Restore failed", e)
                                }
                                goToHome()
                            }
                        } else {
                            askRestoreAfterLogin()
                        }
                    }
                    is Resource.Error -> {
                        setLoading(false)
                        pendingBackupRestoreUri = null
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
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:"
                        ))
                    }
                }
                restoreLauncher.launch(intent)
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
