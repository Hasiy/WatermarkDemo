package com.example.watermarkdemo.processing

/** 參與增強的來源通道。原庫 libYellowWatermark 取 split 後的第 0 個通道，即 BGR 的 B。 */
enum class SourceChannel {
    BLUE,
    GREEN,
    RED,
    LUMA,
}

/** 逐像素曲線模式，對應原庫兩條不同的映射。 */
enum class CurveMode {
    /** extractYellowWatermarkOneChannelOnly：<=150 歸零，151..170 線性，>=171 抬到 191..255。 */
    EXTRACT,

    /** FirstExtractQrFinal 的 useNew 分支：v > dv 加 at255，否則減 rt0。 */
    USE_NEW,
}

/** 管線階段，用於選擇要顯示哪一階段的輸出。順序即處理順序。 */
enum class Stage {
    SOURCE,
    DETECT,
    RECTIFY,
    FLAT_FIELD,
    HIGH_PASS,
    CURVE,
    EQUALIZE,
    STRETCH,
}

/** 管線中用到的固定數值。 */
object EnhanceConstants {
    const val EXTRACT_CLEAR_BELOW = 150
    const val EXTRACT_RAMP_END = 170
    const val EXTRACT_CAP = 235
    const val EXTRACT_BOOST = 20

    const val DEFAULT_FLAT_FIELD_SIGMA = 30f
    const val DEFAULT_FLAT_FIELD_TARGET = 190f
    const val DEFAULT_KERNEL_CENTER = 5f
    const val DEFAULT_MORPH_KERNEL_SIZE = 1
    const val DEFAULT_MORPH_ITERATIONS = 5
    const val DEFAULT_CURVE_DV = 3
    const val DEFAULT_CURVE_AT255 = 0
    const val DEFAULT_CURVE_RT0 = 0

    const val STRETCH_LOW_PERCENT = 1.0
    const val STRETCH_HIGH_PERCENT = 99.0

    /** 光照估計先縮小再模糊，避免對 1080p 做超大高斯核。 */
    const val FLAT_FIELD_DOWNSCALE = 8
    const val LUT_SIZE = 256
    const val MAX_BYTE = 255
}

/**
 * 增強管線參數。
 *
 * @property channel 來源通道
 * @property rectify 是否先檢測紙面四邊形並做透視矯正；找不到四邊形時退回整幅畫面
 * @property flatField 是否先做光照歸一化（原庫沒有此步驟，是為對比額外加的）
 * @property flatFieldSigma 光照估計的高斯 sigma，單位為原圖像素
 * @property flatFieldTarget 歸一化後紙面目標亮度，EXTRACT 曲線假設紙面大於 170
 * @property kernelCenter 3x3 卷積核中心值，其餘為十字 -1；原庫實際核為 [0,-1,0; -1,5,-1; 0,-1,0]（已由反編譯確認，預設 5）
 * @property morphKernelSize 腐蝕/膨脹結構元素邊長，原庫實際為 1x1（空操作），預設與原庫一致
 * @property morphIterations 腐蝕與膨脹各自的次數
 * @property curveMode 逐像素曲線模式
 * @property curveDv USE_NEW 的分界值
 * @property curveAt255 USE_NEW 高於分界時增加的量
 * @property curveRt0 USE_NEW 低於分界時減少的量
 */
data class EnhanceParams(
    val channel: SourceChannel = SourceChannel.BLUE,
    val rectify: Boolean = true,
    val flatField: Boolean = false,
    val flatFieldSigma: Float = EnhanceConstants.DEFAULT_FLAT_FIELD_SIGMA,
    val flatFieldTarget: Float = EnhanceConstants.DEFAULT_FLAT_FIELD_TARGET,
    val kernelCenter: Float = EnhanceConstants.DEFAULT_KERNEL_CENTER,
    val morphKernelSize: Int = EnhanceConstants.DEFAULT_MORPH_KERNEL_SIZE,
    val morphIterations: Int = EnhanceConstants.DEFAULT_MORPH_ITERATIONS,
    val curveMode: CurveMode = CurveMode.EXTRACT,
    val curveDv: Int = EnhanceConstants.DEFAULT_CURVE_DV,
    val curveAt255: Int = EnhanceConstants.DEFAULT_CURVE_AT255,
    val curveRt0: Int = EnhanceConstants.DEFAULT_CURVE_RT0,
)
