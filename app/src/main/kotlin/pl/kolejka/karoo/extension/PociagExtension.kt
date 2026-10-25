package pl.kolejka.karoo.extension

import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.domain.update.UpdateResult
import pl.kolejka.karoo.log.FileLog

class PociagExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    // Wyjątek w zadaniu nigdy nie zabija serwisu (SPEC 9).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> FileLog.e("Nieobsłużony wyjątek w serwisie", e) },
    )

    override val types by lazy {
        listOf(
            TrainDataType(extension) {
                AppContainer.init(this)
                AppContainer.engine
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        FileLog.i("PociagExtension.onCreate")
        scope.launch {
            AppContainer.init(this@PociagExtension)
            // Jednorazowe sprawdzenie przy starcie (SPEC 9.1). Krótko czekamy na połączenie z Karoo,
            // żeby manifest mógł pójść przez Karoo HTTP; bez niego — bezpośrednio.
            withTimeoutOrNull(10_000) { while (!AppContainer.karoo.connected) delay(200) }
            if (!started) {
                started = true
                AppContainer.checkForUpdate()
            }
        }
        // Stan jazdy: odkładanie pobierania w trakcie jazdy bez Wi-Fi i ponowienie po jej końcu.
        scope.launch {
            AppContainer.init(this@PociagExtension)
            var wasRecording = false
            AppContainer.source.recording.distinctUntilChanged().collect { recording ->
                AppContainer.updateEnv.recording = recording
                if (recording) {
                    wasRecording = true
                } else if (wasRecording) {
                    wasRecording = false
                    val r = AppContainer.checkForUpdate()
                    if (r is UpdateResult.SkippedRecent) FileLog.i("Po jeździe: rozkład sprawdzony niedawno")
                }
            }
        }
    }

    override fun onDestroy() {
        FileLog.i("PociagExtension.onDestroy")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "pociag_lodz"

        /** Proces może tworzyć serwis wielokrotnie (P6) — sprawdzenie przy starcie tylko raz. */
        @Volatile private var started = false
    }
}
