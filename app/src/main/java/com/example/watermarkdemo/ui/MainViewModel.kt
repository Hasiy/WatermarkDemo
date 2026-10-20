package com.example.watermarkdemo.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import com.example.watermarkdemo.processing.EnhanceParams
import com.example.watermarkdemo.processing.FrameConverter
import com.example.watermarkdemo.processing.FrameSaver
import com.example.watermarkdemo.processing.Stage
import com.example.watermarkdemo.processing.WatermarkEnhancer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opencv.android.Utils
import org.opencv.core.Mat
import timber.log.Timber

/** 保存按鈕的結果提示。 */
enum class SaveStatus {
    IDLE,
    PENDING,
    SAVED,
    FAILED,
}

/**
 * 主畫面狀態。
 *
 * @property frame 當前選定階段的輸出，尚無畫面時為 null
 * @property frameWidth 輸出寬度（像素）
 * @property frameHeight 輸出高度（像素）
 * @property processMs 單幀處理耗時（毫秒）
 * @property fps 平滑後的處理幀率
 */
data class MainUiState(
    val params: EnhanceParams = EnhanceParams(),
    val stage: Stage = Stage.STRETCH,
    val frame: Bitmap? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val processMs: Long = 0L,
    val fps: Float = 0f,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val enhancer = WatermarkEnhancer()
    private val saveRequested = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var lastFrameNanos = 0L

    fun onParamsChange(transform: (EnhanceParams) -> EnhanceParams) {
        _uiState.update { it.copy(params = transform(it.params)) }
    }

    fun onStageSelected(stage: Stage) {
        _uiState.update { it.copy(stage = stage) }
    }

    fun onSaveClick() {
        saveRequested.set(true)
        _uiState.update { it.copy(saveStatus = SaveStatus.PENDING) }
    }

    /**
     * 供 ImageAnalysis 的背景執行緒呼叫；無論成功與否都會關閉 [image]。
     * 若有待處理的保存請求，會把這一幀的原始畫面與當前階段輸出一起存檔。
     */
    fun onFrame(image: ImageProxy) {
        var rgba: Mat? = null
        try {
            val start = System.nanoTime()
            rgba = FrameConverter.toUprightRgba(image)
            val state = _uiState.value
            val output = enhancer.process(rgba, state.params, state.stage)
            val saveStatus = if (saveRequested.getAndSet(false)) saveFrames(rgba, output) else null
            publish(output, start, saveStatus)
        } catch (e: Exception) {
            Timber.e(e, "process frame failed")
        } finally {
            rgba?.release()
            image.close()
        }
    }

    private fun publish(output: Bitmap, startNanos: Long, saveStatus: SaveStatus?) {
        val now = System.nanoTime()
        val interval = if (lastFrameNanos == 0L) 0L else now - lastFrameNanos
        lastFrameNanos = now
        _uiState.update { s ->
            val instantFps = if (interval > 0) NANOS_PER_SECOND / interval else s.fps
            s.copy(
                frame = output,
                frameWidth = output.width,
                frameHeight = output.height,
                processMs = (now - startNanos) / NANOS_PER_MILLI,
                fps = if (s.fps == 0f) instantFps else s.fps * FPS_KEEP + instantFps * (1 - FPS_KEEP),
                saveStatus = saveStatus ?: s.saveStatus,
            )
        }
    }

    private fun saveFrames(rgba: Mat, processed: Bitmap): SaveStatus {
        val context = getApplication<Application>()
        val raw = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        return try {
            Utils.matToBitmap(rgba, raw)
            val stamp = SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(Date())
            val rawOk = FrameSaver.savePng(context, raw, "${stamp}_raw")
            val outOk = FrameSaver.savePng(context, processed, "${stamp}_${_uiState.value.stage.name.lowercase()}")
            if (rawOk && outOk) SaveStatus.SAVED else SaveStatus.FAILED
        } catch (e: Exception) {
            Timber.e(e, "save frames failed")
            SaveStatus.FAILED
        } finally {
            raw.recycle()
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val NANOS_PER_MILLI = 1_000_000L
        const val FPS_KEEP = 0.8f
        const val FILE_STAMP_PATTERN = "yyyyMMdd_HHmmss"
    }
}
