package pl.kolejka.karoo.extension

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.glance.TileRenderer
import pl.kolejka.karoo.log.FileLog

/**
 * Kafelek „Pociąg do Łodzi" (SPEC 7; tryb awaryjny 6.10). Zawsze emituje własny widok, nigdy pusty (P2);
 * w podglądzie edytora statyczny i nieklikalny (P3).
 */
class TrainDataType(extension: String, private val engine: () -> TileEngine) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        FileLog.i("Kafelek startView $config density=${context.resources.displayMetrics.density}")
        val renderer = TileRenderer(context)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> FileLog.e("Błąd kafelka", e) },
        )
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        scope.launch {
            if (config.preview) {
                val preview = if (BuildConfig.NEARBY_MODE) {
                    TileModel(PreviewData.nearby, stationName = PreviewData.nearbyNames)
                } else {
                    TileModel(PreviewData.state, PreviewData.upcoming, PreviewData.names, PreviewData.finishEta, PreviewData.REMAINING_M)
                }
                emitter.updateView(renderer.render(preview, config))
                return@launch
            }
            var lastAt = 0L
            engine().model.collect { model ->
                // updateView odrzuca wywołania częstsze niż co 900 ms — odczekaj zamiast zgubić stan.
                val wait = 1_000 - (System.currentTimeMillis() - lastAt)
                if (wait > 0) delay(wait)
                emitter.updateView(renderer.render(model, config))
                lastAt = System.currentTimeMillis()
            }
        }
        emitter.setCancellable {
            FileLog.i("Kafelek stopView")
            scope.cancel()
        }
    }

    companion object {
        const val TYPE_ID = "pociag"
    }
}
