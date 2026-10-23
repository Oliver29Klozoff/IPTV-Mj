package com.iptvapp.service

import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

interface RecordingProviderAccess {
    suspend fun key(server: Int): String?
    suspend fun limits(server: Int): XtreamRepository.AccountLimits?
    suspend fun url(server: Int, stream: Int): String
}

class DefaultRecordingProviderAccess @Inject constructor(
    private val prefs: PreferencesManager, private val repository: XtreamRepository
) : RecordingProviderAccess {
    override suspend fun key(server: Int): String? {
        val identity = if (server == -1) {
            val c = prefs.credentials.first()
            if (!c.isLoggedIn) return null
            "${c.serverUrl.trimEnd('/')}|${c.username}"
        } else {
            val s = prefs.getExtraServersWithNick().getOrNull(server) ?: return null
            if (!s.getOrElse(5) { "true" }.toBoolean()) return null
            "${s[0].trimEnd('/')}|${s[1]}"
        }
        return SeriesRecordingPolicy.hash(identity)
    }
    override suspend fun limits(server: Int) = repository.accountLimits(server)
    override suspend fun url(server: Int, stream: Int) = if (server == -1)
        repository.getLiveStreamUrlForRecording(stream) else repository.getMergedLiveStreamUrlForRecording(server, stream)
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class RecordingProviderModule {
    @dagger.Binds abstract fun access(implementation: DefaultRecordingProviderAccess): RecordingProviderAccess
}
