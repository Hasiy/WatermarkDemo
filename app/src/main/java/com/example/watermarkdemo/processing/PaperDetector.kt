package com.example.watermarkdemo.processing

import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 紙面檢測與透視矯正用到的數值，來自對 libGABStamp 的反編譯並按縮小後的畫面換算。 */
object PaperDetectConstants {
    /** 檢測時把長邊縮到這個值，原庫在更大的尺寸上做，核大小按比例換算。 */
    const val DETECT_LONG_SIDE = 640.0
    const val OPEN_KERNEL_SIZE = 5.0
    const val CLOSE_KERNEL_SIZE = 31.0
    const val ADAPTIVE_BLOCK_SIZE = 27
    const val ADAPTIVE_C = 7.0
    const val POLY_EPSILON_RATIO = 0.02
    const val MIN_AREA_RATIO = 0.1
    const val MAX_AREA_RATIO = 0.9
    const val QUAD_VERTEX_COUNT = 4
    const val RECTIFY_MAX_SIDE = 1280.0
    const val MIN_RECTIFY_SIDE = 64.0
    const val OVERLAY_THICKNESS = 4

    /** Otsu 兜底時逐步放寬的多邊形近似精度（相對周長）。 */
    val HULL_EPSILON_LADDER = doubleArrayOf(0.02, 0.03, 0.04, 0.06)
}

/**
 * 在畫面裡找最大的凸四邊形（預期是紙面或印章所在區域），並做透視矯正。
 * 流程：灰階 -> 縮小 -> 開運算 -> 閉運算 -> 自適應閾值 -> 外輪廓 -> 多邊形近似 -> 面積與凸性篩選。
 */
object PaperDetector {

    /**
     * @param rgba CV_8UC4、RGBA 排列的畫面；不會被修改
     * @return 以 左上、右上、右下、左下 排序、單位為原圖像素的四個角點；找不到時回傳 null
     */
    fun find(rgba: Mat): Array<Point>? {
        val gray = Mat()
        val small = Mat()
        val opened = Mat()
        val closed = Mat()
        val binary = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val scale = minOf(1.0, PaperDetectConstants.DETECT_LONG_SIDE / maxOf(gray.cols(), gray.rows()))
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)

            val open = rectKernel(PaperDetectConstants.OPEN_KERNEL_SIZE)
            val close = rectKernel(PaperDetectConstants.CLOSE_KERNEL_SIZE)
            try {
                Imgproc.erode(small, opened, open)
                Imgproc.dilate(opened, opened, open)
                Imgproc.dilate(opened, closed, close)
                Imgproc.erode(closed, closed, close)
            } finally {
                open.release()
                close.release()
            }

            Imgproc.adaptiveThreshold(
                closed, binary, MAX_BINARY_VALUE, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY,
                PaperDetectConstants.ADAPTIVE_BLOCK_SIZE, PaperDetectConstants.ADAPTIVE_C,
            )
            val frameArea = small.cols().toDouble() * small.rows()
            var best = pickLargestQuad(binary, frameArea, useHull = false)
            if (best == null) {
                // 原庫的自適應閾值在「紙放在均勻桌面上」時會把兩邊都判成白色，退回 Otsu
                Imgproc.threshold(closed, binary, 0.0, MAX_BINARY_VALUE, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                best = pickLargestQuad(binary, frameArea, useHull = true)
            }
            val quad = best ?: return null
            return orderCorners(quad).map { Point(it.x / scale, it.y / scale) }.toTypedArray()
        } finally {
            gray.release()
            small.release()
            opened.release()
            closed.release()
            binary.release()
        }
    }

    /**
     * 按四邊長度估算輸出尺寸，把 [quad] 圍出的區域拉正。
     *
     * @param src 單通道圖
     * @param quad [find] 回傳的有序角點
     * @return 矯正後的新 Mat，呼叫端負責 release
     */
    fun rectify(src: Mat, quad: Array<Point>): Mat {
        val top = distance(quad[0], quad[1])
        val bottom = distance(quad[3], quad[2])
        val left = distance(quad[0], quad[3])
        val right = distance(quad[1], quad[2])
        var width = maxOf(top, bottom)
        var height = maxOf(left, right)
        val shrink = minOf(1.0, PaperDetectConstants.RECTIFY_MAX_SIDE / maxOf(width, height))
        width = maxOf(width * shrink, PaperDetectConstants.MIN_RECTIFY_SIDE)
        height = maxOf(height * shrink, PaperDetectConstants.MIN_RECTIFY_SIDE)

        val from = MatOfPoint2f(*quad)
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(width - 1, 0.0), Point(width - 1, height - 1), Point(0.0, height - 1),
        )
        val transform = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        try {
            Imgproc.warpPerspective(src, out, transform, Size(width, height), Imgproc.INTER_LINEAR)
        } finally {
            from.release()
            to.release()
            transform.release()
        }
        return out
    }

    /**
     * 把檢測到的四邊形畫在單通道圖上，輸出 3 通道方便肉眼查看。
     *
     * @param gray 單通道圖
     * @param quad 角點；為 null 時只做通道擴展
     */
    fun drawOverlay(gray: Mat, quad: Array<Point>?): Mat {
        val out = Mat()
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGB)
        if (quad != null) {
            val contour = MatOfPoint(*quad.map { Point(it.x, it.y) }.toTypedArray())
            try {
                Imgproc.polylines(
                    out, listOf(contour), true, Scalar(0.0, 255.0, 0.0), PaperDetectConstants.OVERLAY_THICKNESS,
                )
            } finally {
                contour.release()
            }
        }
        return out
    }

    /**
     * 在二值圖的外輪廓中取面積落在 [10%, 90%] 的最大凸四邊形。
     *
     * @param useHull false 為原庫做法（嚴格 4 頂點且凸）；true 為本專案兜底：先取凸包並逐步放寬近似精度，容忍紙邊卷曲
     */
    private fun pickLargestQuad(binary: Mat, frameArea: Double, useHull: Boolean): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        var bestArea = 0.0
        var best: Array<Point>? = null
        try {
            for (contour in contours) {
                val approx = approximateQuad(contour, useHull) ?: continue
                try {
                    val area = Math.abs(Imgproc.contourArea(approx))
                    val inRange = area >= frameArea * PaperDetectConstants.MIN_AREA_RATIO &&
                        area <= frameArea * PaperDetectConstants.MAX_AREA_RATIO
                    if (inRange && area > bestArea) {
                        bestArea = area
                        best = approx.toArray()
                    }
                } finally {
                    approx.release()
                }
            }
        } finally {
            contours.forEach { it.release() }
        }
        return best
    }

    /** 回傳 4 頂點的凸多邊形，不符合時回傳 null；回傳的 Mat 由呼叫端 release。 */
    private fun approximateQuad(contour: MatOfPoint, useHull: Boolean): MatOfPoint? {
        val source = if (useHull) hullOf(contour) else contour
        val curve = MatOfPoint2f(*source.toArray())
        val ratios = if (useHull) PaperDetectConstants.HULL_EPSILON_LADDER else doubleArrayOf(PaperDetectConstants.POLY_EPSILON_RATIO)
        try {
            val perimeter = Imgproc.arcLength(curve, true)
            for (ratio in ratios) {
                val approx2f = MatOfPoint2f()
                val approx = MatOfPoint()
                try {
                    Imgproc.approxPolyDP(curve, approx2f, perimeter * ratio, true)
                    approx.fromArray(*approx2f.toArray())
                    if (approx.total() == PaperDetectConstants.QUAD_VERTEX_COUNT.toLong() && Imgproc.isContourConvex(approx)) {
                        return approx
                    }
                    approx.release()
                } finally {
                    approx2f.release()
                }
            }
            return null
        } finally {
            curve.release()
            if (useHull) source.release()
        }
    }

    private fun hullOf(contour: MatOfPoint): MatOfPoint {
        val indices = MatOfInt()
        try {
            Imgproc.convexHull(contour, indices)
            val points = contour.toArray()
            return MatOfPoint(*indices.toArray().map { points[it] }.toTypedArray())
        } finally {
            indices.release()
        }
    }

    /** 以 x+y 最小為左上、最大為右下，y-x 最小為右上、最大為左下。 */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun rectKernel(size: Double): Mat =
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size))

    private fun distance(a: Point, b: Point): Double = Math.hypot(a.x - b.x, a.y - b.y)

    private const val MAX_BINARY_VALUE = 255.0
}
