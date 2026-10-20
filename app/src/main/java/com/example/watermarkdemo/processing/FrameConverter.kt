package com.example.watermarkdemo.processing

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/** 把 CameraX 的 RGBA_8888 幀轉成直立的 OpenCV Mat。 */
object FrameConverter {

    private const val BYTES_PER_PIXEL = 4
    private const val ROTATE_90 = 90
    private const val ROTATE_180 = 180
    private const val ROTATE_270 = 270

    /**
     * @param image 輸出格式必須是 OUTPUT_IMAGE_FORMAT_RGBA_8888 的幀；本函式不會關閉它
     * @return CV_8UC4、RGBA 排列、依 rotationDegrees 轉為直立的新 Mat，呼叫端負責 release
     */
    fun toUprightRgba(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val width = image.width
        val height = image.height
        val full = Mat(height, plane.rowStride / BYTES_PER_PIXEL, CvType.CV_8UC4)
        val cropped: Mat
        try {
            full.put(0, 0, bytes)
            cropped = full.submat(0, height, 0, width)
        } catch (e: Exception) {
            full.release()
            throw e
        }

        val upright = Mat()
        try {
            when (image.imageInfo.rotationDegrees) {
                ROTATE_90 -> Core.rotate(cropped, upright, Core.ROTATE_90_CLOCKWISE)
                ROTATE_180 -> Core.rotate(cropped, upright, Core.ROTATE_180)
                ROTATE_270 -> Core.rotate(cropped, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> cropped.copyTo(upright)
            }
        } finally {
            cropped.release()
            full.release()
        }
        return upright
    }
}
