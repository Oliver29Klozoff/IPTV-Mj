package com.iptvapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.BuildConfig
import com.iptvapp.R
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivitySplashBinding
import com.iptvapp.ui.home.HomeActivity
import com.iptvapp.ui.home.TvHomeActivity
import com.iptvapp.ui.login.LoginActivity
import com.iptvapp.util.isLargeScreenDevice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var prefs: PreferencesManager
    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearCrashLogIfNewVersion()
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvVersion?.text = "Version ${BuildConfig.VERSION_NAME}"
        val anim = AnimationUtils.loadAnimation(this, R.anim.splash_in)
        binding.ivSplashLogo.startAnimation(anim)
        binding.tvSplashName.startAnimation(anim)
        lifecycleScope.launch {
            delay(4000)
            val creds = prefs.credentials.first()
            val intent = if (creds.isLoggedIn) {
                val homeClass = if (isLargeScreenDevice()) TvHomeActivity::class.java else HomeActivity::class.java
                Intent(this@SplashActivity, homeClass)
            } else {
                Intent(this@SplashActivity, LoginActivity::class.java)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun clearCrashLogIfNewVersion() {
        try {
            val prefs = getSharedPreferences("mktv_prefs", MODE_PRIVATE)
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val currentCode = pInfo.longVersionCode
            val lastCode = prefs.getLong("last_version_code", 0L)
            if (currentCode != lastCode) {
                java.io.File(filesDir, "crash_log.txt").delete()
                prefs.edit().putLong("last_version_code", currentCode).apply()
            }
        } catch (_: Exception) {}
    }
}