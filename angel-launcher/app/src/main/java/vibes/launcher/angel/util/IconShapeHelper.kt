package vibes.launcher.angel.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import vibes.launcher.angel.data.SettingsManager

object IconShapeHelper {

    fun createShapePath(shape: Int, width: Float, height: Float): Path? {
        if (shape == SettingsManager.SHAPE_SYSTEM) return null
        val path = Path()
        val rect = RectF(0f, 0f, width, height)
        when (shape) {
            SettingsManager.SHAPE_CIRCLE -> {
                path.addOval(rect, Path.Direction.CW)
            }
            SettingsManager.SHAPE_SQUIRCLE -> {
                val radius = width * 0.35f
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            SettingsManager.SHAPE_ROUNDED_SQUARE -> {
                val radius = width * 0.18f
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            SettingsManager.SHAPE_TEARDROP -> {
                val r = width * 0.48f
                val sharpR = width * 0.08f
                val radii = floatArrayOf(r, r, r, r, sharpR, sharpR, r, r)
                path.addRoundRect(rect, radii, Path.Direction.CW)
            }
            SettingsManager.SHAPE_HEXAGON -> {
                val cx = width / 2f
                path.moveTo(cx, 0f)
                path.lineTo(width, height * 0.25f)
                path.lineTo(width, height * 0.75f)
                path.lineTo(cx, height)
                path.lineTo(0f, height * 0.75f)
                path.lineTo(0f, height * 0.25f)
                path.close()
            }
            else -> return null
        }
        return path
    }

    fun applyShapeMask(bitmap: Bitmap, shape: Int): Bitmap {
        if (shape == SettingsManager.SHAPE_SYSTEM) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return bitmap
        val path = createShapePath(shape, w.toFloat(), h.toFloat()) ?: return bitmap

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

        // Draw shape mask
        canvas.drawPath(path, paint)

        // Draw icon bitmap using SRC_IN (keeps only inside shape)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return output
    }

    fun applyShapeToView(view: View, shape: Int) {
        if (shape == SettingsManager.SHAPE_SYSTEM) {
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = false
            return
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val w = v.width.takeIf { it > 0 } ?: v.layoutParams?.width?.takeIf { it > 0 } ?: 144
                val h = v.height.takeIf { it > 0 } ?: v.layoutParams?.height?.takeIf { it > 0 } ?: 144
                when (shape) {
                    SettingsManager.SHAPE_CIRCLE -> {
                        outline.setOval(0, 0, w, h)
                    }
                    SettingsManager.SHAPE_SQUIRCLE -> {
                        outline.setRoundRect(0, 0, w, h, w * 0.35f)
                    }
                    SettingsManager.SHAPE_ROUNDED_SQUARE -> {
                        outline.setRoundRect(0, 0, w, h, w * 0.18f)
                    }
                    SettingsManager.SHAPE_TEARDROP, SettingsManager.SHAPE_HEXAGON -> {
                        val path = createShapePath(shape, w.toFloat(), h.toFloat())
                        if (path != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                outline.setPath(path)
                            } catch (e: Exception) {
                                outline.setRoundRect(0, 0, w, h, w * 0.25f)
                            }
                        } else {
                            outline.setRoundRect(0, 0, w, h, w * 0.25f)
                        }
                    }
                    else -> {
                        outline.setRoundRect(0, 0, w, h, 0f)
                    }
                }
            }
        }
        view.clipToOutline = true
    }
}
