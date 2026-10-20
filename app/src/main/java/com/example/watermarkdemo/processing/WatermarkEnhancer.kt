package com.example.watermarkdemo.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 一次處理的結果。
 *
 * @property bitmap 指定階段的輸出，呼叫端擁有所有權
 * @property paperFound 是否檢測到紙面四邊形；未開啟矯正時為 null
 */
class EnhanceResult(val bitmap: Bitmap, val paperFound: Boolean?)

/**
 * 複現 libYellowWatermark.so 的「一級水印」增強管線（由反編譯結果推斷，非逐位元一致）。
 *
 * 順序：取通道 -> (紙面檢測 + 透視矯正) -> (光照歸一化) -> 3x3 卷積 -> 腐蝕x N -> 膨脹x N -> 曲線 -> 直方圖均衡 -> 分位拉伸。
 * 非執行緒安全，由呼叫端保證同一時間只在一個執行緒使用。
 */
class WatermarkEnhancer {

    /**
     * 處理一幀並回傳指定階段的灰階結果。
     *
     * @param rgba CV_8UC4、RGBA 排列、已旋轉為直立的畫面；本函式不會修改也不會釋放它
     * @param params 管線參數
     * @param upTo 要輸出的階段，後面的階段不會執行
     * @return 輸出圖與紙面檢測結果
     */
    fun process(rgba: Mat, params: EnhanceParams, upTo: Stage): EnhanceResult {
        val owned = ArrayList<Mat>()
        fun <T : Mat> T.track(): T = also { owned.add(it) }
        try {
            var cur = extractChannel(rgba, params.channel).track()
            if (upTo == Stage.SOURCE) return EnhanceResult(cur.toBitmap(), null)

            val quad = if (params.rectify) PaperDetector.find(rgba) else null
            val paperFound = if (params.rectify) quad != null else null
            if (upTo == Stage.DETECT) {
                return EnhanceResult(PaperDetector.drawOverlay(cur, quad).track().toBitmap(), paperFound)
            }

            if (quad != null) cur = PaperDetector.rectify(cur, quad).track()
            if (upTo == Stage.RECTIFY) return EnhanceResult(cur.toBitmap(), paperFound)

            if (params.flatField) {
                cur = flatField(cur, params.flatFieldSigma, params.flatFieldTarget).track()
            }
            if (upTo == Stage.FLAT_FIELD) return EnhanceResult(cur.toBitmap(), paperFound)

            cur = highPassAndMorph(cur, params).track()
            if (upTo == Stage.HIGH_PASS) return EnhanceResult(cur.toBitmap(), paperFound)

            cur = applyCurve(cur, params).track()
            if (upTo == Stage.CURVE) return EnhanceResult(cur.toBitmap(), paperFound)

            cur = Mat().track().also { Imgproc.equalizeHist(cur, it) }
            if (upTo == Stage.EQUALIZE) return EnhanceResult(cur.toBitmap(), paperFound)

            cur = percentileStretch(cur).track()
            return EnhanceResult(cur.toBitmap(), paperFound)
        } finally {
            owned.forEach { it.release() }
        }
    }

    private fun extractChannel(rgba: Mat, channel: SourceChannel): Mat {
        val out = Mat()
        when (channel) {
            SourceChannel.RED -> Core.extractChannel(rgba, out, RGBA_RED)
            SourceChannel.GREEN -> Core.extractChannel(rgba, out, RGBA_GREEN)
            SourceChannel.BLUE -> Core.extractChannel(rgba, out, RGBA_BLUE)
            SourceChannel.LUMA -> Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2GRAY)
        }
        return out
    }

    /** 除以大尺度背景以壓掉光照漸變與陰影；背景在縮小圖上估計。 */
    private fun flatField(src: Mat, sigma: Float, target: Float): Mat {
        val scale = EnhanceConstants.FLAT_FIELD_DOWNSCALE
        val f = Mat()
        val small = Mat()
        val bgSmall = Mat()
        val bg = Mat()
        val ratio = Mat()
        val out = Mat()
        try {
            src.convertTo(f, CvType.CV_32F)
            Core.add(f, Scalar(1.0), f)
            Imgproc.resize(f, small, Size(), 1.0 / scale, 1.0 / scale, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, bgSmall, Size(), (sigma / scale).toDouble())
            Imgproc.resize(bgSmall, bg, f.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.add(bg, Scalar(1.0), bg)
            Core.divide(f, bg, ratio, target.toDouble())
            ratio.convertTo(out, CvType.CV_8U)
        } finally {
            f.release()
            small.release()
            bgSmall.release()
            bg.release()
            ratio.release()
        }
        return out
    }

    private fun highPassAndMorph(src: Mat, params: EnhanceParams): Mat {
        val kernel = Mat(KERNEL_SIZE, KERNEL_SIZE, CvType.CV_32F)
        val sharpened = Mat()
        try {
            val c = params.kernelCenter
            kernel.put(0, 0, floatArrayOf(0f, -1f, 0f, -1f, c, -1f, 0f, -1f, 0f))
            Imgproc.filter2D(src, sharpened, -1, kernel)
        } finally {
            kernel.release()
        }
        if (params.morphKernelSize <= 1 || params.morphIterations <= 0) return sharpened

        val element = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(params.morphKernelSize.toDouble(), params.morphKernelSize.toDouble()),
        )
        val eroded = Mat()
        val out = Mat()
        try {
            val anchor = Point(-1.0, -1.0)
            Imgproc.erode(sharpened, eroded, element, anchor, params.morphIterations)
            Imgproc.dilate(eroded, out, element, anchor, params.morphIterations)
        } finally {
            element.release()
            sharpened.release()
            eroded.release()
        }
        return out
    }

    private fun applyCurve(src: Mat, params: EnhanceParams): Mat {
        val lutBytes = ByteArray(EnhanceConstants.LUT_SIZE) { v ->
            val mapped = when (params.curveMode) {
                CurveMode.EXTRACT -> extractCurve(v)
                CurveMode.USE_NEW -> useNewCurve(v, params)
            }
            mapped.coerceIn(0, EnhanceConstants.MAX_BYTE).toByte()
        }
        val lut = Mat(1, EnhanceConstants.LUT_SIZE, CvType.CV_8U)
        val out = Mat()
        try {
            lut.put(0, 0, lutBytes)
            Core.LUT(src, lut, out)
        } finally {
            lut.release()
        }
        return out
    }

    private fun extractCurve(v: Int): Int =
        if (v > EnhanceConstants.EXTRACT_RAMP_END) {
            minOf(v, EnhanceConstants.EXTRACT_CAP) + EnhanceConstants.EXTRACT_BOOST
        } else {
            maxOf(v, EnhanceConstants.EXTRACT_CLEAR_BELOW) - EnhanceConstants.EXTRACT_CLEAR_BELOW
        }

    private fun useNewCurve(v: Int, params: EnhanceParams): Int =
        if (v > params.curveDv) v + params.curveAt255 else v - params.curveRt0

    /** 用累積直方圖找 1%~99% 分位並線性拉伸，近似原庫的 contrastStretch1。 */
    private fun percentileStretch(src: Mat): Mat {
        val hist = Mat()
        val out = Mat()
        try {
            Imgproc.calcHist(
                listOf(src), MatOfInt(0), Mat(), hist,
                MatOfInt(EnhanceConstants.LUT_SIZE), MatOfFloat(0f, EnhanceConstants.LUT_SIZE.toFloat()),
            )
            val counts = FloatArray(EnhanceConstants.LUT_SIZE)
            hist.get(0, 0, counts)
            val total = counts.sum()
            var acc = 0f
            var lo = 0
            var hi = EnhanceConstants.MAX_BYTE
            var loFound = false
            for (i in counts.indices) {
                acc += counts[i]
                val pct = acc / total * PERCENT_SCALE
                if (!loFound && pct >= EnhanceConstants.STRETCH_LOW_PERCENT) {
                    lo = i
                    loFound = true
                }
                if (pct >= EnhanceConstants.STRETCH_HIGH_PERCENT) {
                    hi = i
                    break
                }
            }
            if (hi <= lo) return src.clone()
            val alpha = EnhanceConstants.MAX_BYTE.toDouble() / (hi - lo)
            src.convertTo(out, CvType.CV_8U, alpha, -lo * alpha)
        } finally {
            hist.release()
        }
        return out
    }

    private fun Mat.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(this, bitmap)
        return bitmap
    }

    private companion object {
        const val RGBA_RED = 0
        const val RGBA_GREEN = 1
        const val RGBA_BLUE = 2
        const val KERNEL_SIZE = 3
        const val PERCENT_SCALE = 100f
    }
}
