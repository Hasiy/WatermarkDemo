package com.example.watermarkdemo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.watermarkdemo.R
import com.example.watermarkdemo.processing.CurveMode
import com.example.watermarkdemo.processing.EnhanceParams
import com.example.watermarkdemo.processing.SourceChannel
import com.example.watermarkdemo.processing.Stage
import java.util.concurrent.Executors
import timber.log.Timber

private const val ANALYSIS_WIDTH = 1920
private const val ANALYSIS_HEIGHT = 1080
private const val FRAME_WEIGHT = 1f
private const val PANEL_WEIGHT = 1f

private const val SIGMA_MIN = 5f
private const val SIGMA_MAX = 100f
private const val TARGET_MIN = 120f
private const val TARGET_MAX = 230f
private const val CENTER_MIN = 1f
private const val CENTER_MAX = 9f
private const val MORPH_SIZE_MAX = 7f
private const val MORPH_ITER_MAX = 8f
private const val CURVE_DV_MAX = 255f
private const val CURVE_DELTA_MAX = 100f

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    if (!granted) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.permission_rationale))
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(R.string.action_grant_camera)) }
        }
        return
    }

    CameraAnalysisEffect(viewModel)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(FRAME_WEIGHT).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val frame = state.frame
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_output_frame),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(stringResource(R.string.status_waiting_frame), color = Color.White)
            }
        }

        Text(
            text = stringResource(
                R.string.status_line,
                state.frameWidth, state.frameHeight, state.fps, state.processMs,
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )

        ControlPanel(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxWidth().weight(PANEL_WEIGHT),
        )
    }
}

/** 綁定 ImageAnalysis（RGBA、1080p），離開組合時解除綁定並關閉執行緒。 */
@Composable
private fun CameraAnalysisEffect(viewModel: MainViewModel) {
    val context = LocalContext.current
    val owner = context as ComponentActivity
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { viewModel.onFrame(it) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Timber.e(e, "bind camera failed")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}

@Composable
private fun ControlPanel(state: MainUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val p = state.params
    fun update(transform: (EnhanceParams) -> EnhanceParams) = viewModel.onParamsChange(transform)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle(R.string.section_stage)
        ChipRow(Stage.entries, state.stage, { stageLabel(it) }) { viewModel.onStageSelected(it) }

        SectionTitle(R.string.section_channel)
        ChipRow(SourceChannel.entries, p.channel, { channelLabel(it) }) { c -> update { it.copy(channel = c) } }

        SwitchRow(R.string.label_flat_field, p.flatField) { on -> update { it.copy(flatField = on) } }
        if (p.flatField) {
            ParamSlider(R.string.label_flat_sigma, p.flatFieldSigma, SIGMA_MIN..SIGMA_MAX) { v ->
                update { it.copy(flatFieldSigma = v) }
            }
            ParamSlider(R.string.label_flat_target, p.flatFieldTarget, TARGET_MIN..TARGET_MAX) { v ->
                update { it.copy(flatFieldTarget = v) }
            }
        }

        SectionTitle(R.string.section_highpass)
        ParamSlider(R.string.label_kernel_center, p.kernelCenter, CENTER_MIN..CENTER_MAX) { v ->
            update { it.copy(kernelCenter = v) }
        }
        ParamSlider(R.string.label_morph_size, p.morphKernelSize.toFloat(), 1f..MORPH_SIZE_MAX, steps = 5) { v ->
            update { it.copy(morphKernelSize = v.toInt()) }
        }
        ParamSlider(R.string.label_morph_iter, p.morphIterations.toFloat(), 0f..MORPH_ITER_MAX, steps = 7) { v ->
            update { it.copy(morphIterations = v.toInt()) }
        }

        SectionTitle(R.string.section_curve)
        ChipRow(CurveMode.entries, p.curveMode, { curveLabel(it) }) { m -> update { it.copy(curveMode = m) } }
        if (p.curveMode == CurveMode.USE_NEW) {
            ParamSlider(R.string.label_curve_dv, p.curveDv.toFloat(), 0f..CURVE_DV_MAX) { v ->
                update { it.copy(curveDv = v.toInt()) }
            }
            ParamSlider(R.string.label_curve_at255, p.curveAt255.toFloat(), 0f..CURVE_DELTA_MAX) { v ->
                update { it.copy(curveAt255 = v.toInt()) }
            }
            ParamSlider(R.string.label_curve_rt0, p.curveRt0.toFloat(), 0f..CURVE_DELTA_MAX) { v ->
                update { it.copy(curveRt0 = v.toInt()) }
            }
        }

        Button(onClick = viewModel::onSaveClick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save_frame))
        }
        Text(
            text = stringResource(saveStatusText(state.saveStatus)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun SectionTitle(@StringRes id: Int) {
    Text(stringResource(id), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(stringResource(label(item))) },
            )
        }
    }
}

@Composable
private fun SwitchRow(@StringRes id: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(id))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ParamSlider(
    @StringRes id: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "${stringResource(id)}: ${"%.1f".format(value)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

private fun stageLabel(stage: Stage): Int = when (stage) {
    Stage.SOURCE -> R.string.stage_source
    Stage.FLAT_FIELD -> R.string.stage_flat_field
    Stage.HIGH_PASS -> R.string.stage_high_pass
    Stage.CURVE -> R.string.stage_curve
    Stage.EQUALIZE -> R.string.stage_equalize
    Stage.STRETCH -> R.string.stage_stretch
}

private fun channelLabel(channel: SourceChannel): Int = when (channel) {
    SourceChannel.BLUE -> R.string.channel_blue
    SourceChannel.GREEN -> R.string.channel_green
    SourceChannel.RED -> R.string.channel_red
    SourceChannel.LUMA -> R.string.channel_luma
}

private fun curveLabel(mode: CurveMode): Int = when (mode) {
    CurveMode.EXTRACT -> R.string.curve_extract
    CurveMode.USE_NEW -> R.string.curve_use_new
}

private fun saveStatusText(status: SaveStatus): Int = when (status) {
    SaveStatus.IDLE -> R.string.save_idle
    SaveStatus.PENDING -> R.string.save_pending
    SaveStatus.SAVED -> R.string.save_saved
    SaveStatus.FAILED -> R.string.save_failed
}
