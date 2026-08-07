package vibes.launcher.angel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IconExtractor {
    companion object {
        val INSTANCE = IconExtractor()
    }

    suspend fun extractAndRasterizeIcon(
        context: Context,
        packageName: String,
        iconPackPackage: String?,
        resources: IconPackResources?,
        targetSizePx: Int,
        useDefaultIcon: Boolean,
        customIconPack: String? = null,
        customIconDrawable: String? = null,
        iconShape: Int = vibes.launcher.angel.data.SettingsManager.SHAPE_SYSTEM
    ): Bitmap? = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 1. Check custom icon selection
        if (customIconDrawable != null) {
            if (customIconPack == "system" || customIconDrawable == "system") {
                try {
                    val systemDrawable = pm.getApplicationIcon(packageName)
                    return@withContext rasterizeDrawable(systemDrawable, targetSizePx, null, null, null, iconShape, packageName)
                } catch (e: Exception) {
                    return@withContext null
                }
            } else if (customIconPack == "builtin") {
                try {
                    val resId = context.resources.getIdentifier(customIconDrawable, "drawable", context.packageName)
                    if (resId != 0) {
                        val d = androidx.core.content.ContextCompat.getDrawable(context, resId)
                        if (d != null) {
                            return@withContext rasterizeDrawable(d, targetSizePx, null, null, null, iconShape, packageName)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IconExtractor", "Failed to load custom builtin icon", e)
                }
            } else if (customIconPack != null) {
                try {
                    val packRes = pm.getResourcesForApplication(customIconPack)
                    val resId = packRes.getIdentifier(customIconDrawable, "drawable", customIconPack)
                    if (resId != 0) {
                        val d = packRes.getDrawable(resId, null)
                        if (d != null) {
                            return@withContext rasterizeDrawable(d, targetSizePx, null, null, null, iconShape, packageName)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IconExtractor", "Failed to load custom pack icon", e)
                }
            }
        }

        // 2. Themed icon from active icon pack
        var themedDrawable: Drawable? = null

        if (!useDefaultIcon && iconPackPackage != null && resources != null) {
            val drawableName = resources.appFilterMap[packageName]
            if (drawableName != null) {
                try {
                    val packRes = pm.getResourcesForApplication(iconPackPackage)
                    val resId = packRes.getIdentifier(drawableName, "drawable", iconPackPackage)
                    if (resId != 0) {
                        themedDrawable = packRes.getDrawable(resId, null)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IconExtractor", "Failed to load themed icon from pack", e)
                }
            }
        }

        if (themedDrawable != null) {
            return@withContext rasterizeDrawable(themedDrawable, targetSizePx, null, null, null, iconShape, packageName)
        }

        // 3. Fallback to system icon
        try {
            val systemDrawable = pm.getApplicationIcon(packageName)
            if (!useDefaultIcon && iconPackPackage != null && resources != null) {
                return@withContext rasterizeDrawable(systemDrawable, targetSizePx, resources, iconPackPackage, context, iconShape, packageName)
            } else {
                return@withContext rasterizeDrawable(systemDrawable, targetSizePx, null, null, null, iconShape, packageName)
            }
        } catch (e: Exception) {
            return@withContext null
        }
    }

    suspend fun loadIconPackDrawable(
        context: Context,
        iconPackPackage: String,
        drawableName: String,
        targetSizePx: Int,
        iconShape: Int = vibes.launcher.angel.data.SettingsManager.SHAPE_SYSTEM
    ): Bitmap? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        try {
            val packRes = pm.getResourcesForApplication(iconPackPackage)
            val resId = packRes.getIdentifier(drawableName, "drawable", iconPackPackage)
            if (resId != 0) {
                val d = packRes.getDrawable(resId, null)
                if (d != null) {
                    return@withContext rasterizeDrawable(d, targetSizePx, null, null, null, iconShape, null)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("IconExtractor", "Failed to load drawable $drawableName from pack $iconPackPackage", e)
        }
        null
    }

    suspend fun loadSystemIcon(
        context: Context,
        packageName: String,
        targetSizePx: Int,
        iconShape: Int = vibes.launcher.angel.data.SettingsManager.SHAPE_SYSTEM
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val d = context.packageManager.getApplicationIcon(packageName)
            rasterizeDrawable(d, targetSizePx, null, null, null, iconShape, packageName)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadBuiltinDrawable(
        context: Context,
        resId: Int,
        targetSizePx: Int,
        iconShape: Int = vibes.launcher.angel.data.SettingsManager.SHAPE_SYSTEM
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val d = androidx.core.content.ContextCompat.getDrawable(context, resId)
            if (d != null) {
                rasterizeDrawable(d, targetSizePx, null, null, null, iconShape, null)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun rasterizeDrawable(
        drawable: Drawable,
        iconSize: Int,
        resources: IconPackResources?,
        iconPackPackage: String?,
        context: Context?,
        iconShape: Int = vibes.launcher.angel.data.SettingsManager.SHAPE_SYSTEM,
        packageName: String? = null
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable && iconShape != vibes.launcher.angel.data.SettingsManager.SHAPE_SYSTEM) {
            val bg = drawable.background
            val fg = drawable.foreground

            val baseBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            val baseCanvas = Canvas(baseBitmap)

            // Android spec safe-zone math: bleed is iconSize / 6
            val bleed = iconSize / 6
            val left = -bleed
            val top = -bleed
            val right = iconSize + bleed
            val bottom = iconSize + bleed

            if (bg != null) {
                bg.setBounds(left, top, right, bottom)
                bg.draw(baseCanvas)
            }
            if (fg != null) {
                fg.setBounds(left, top, right, bottom)
                fg.draw(baseCanvas)
            }

            val masked = vibes.launcher.angel.util.IconShapeHelper.applyShapeMask(baseBitmap, iconShape)
            if (masked != baseBitmap) {
                baseBitmap.recycle()
            }
            return masked
        }

        val baseBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val baseCanvas = Canvas(baseBitmap)
        drawable.setBounds(0, 0, iconSize, iconSize)
        drawable.draw(baseCanvas)

        if (resources == null || iconPackPackage == null || context == null) {
            val masked = vibes.launcher.angel.util.IconShapeHelper.applyShapeMask(baseBitmap, iconShape)
            if (masked != baseBitmap) {
                baseBitmap.recycle()
            }
            return masked
        }

        // Apply IconBack, IconMask, and IconUpon
        val finalBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val pm = context.packageManager
        try {
            val packRes = pm.getResourcesForApplication(iconPackPackage)

            // 1. Draw IconBack (selected deterministically based on hash code)
            if (resources.iconBacks.isNotEmpty()) {
                val backIndex = if (packageName != null) {
                    Math.abs(packageName.hashCode()) % resources.iconBacks.size
                } else {
                    0
                }
                val backName = resources.iconBacks[backIndex]
                loadBitmapFromPack(packRes, backName, iconPackPackage)?.let { back ->
                    val scaledBack = Bitmap.createScaledBitmap(back, iconSize, iconSize, true)
                    canvas.drawBitmap(scaledBack, 0f, 0f, paint)
                    if (scaledBack != back) scaledBack.recycle()
                    back.recycle()
                }
            }

            // 2. Draw scaled unthemed icon
            val scale = resources.iconScale
            val scaledWidth = (iconSize * scale).toInt()
            val scaledHeight = (iconSize * scale).toInt()
            val scaledBase = Bitmap.createScaledBitmap(baseBitmap, scaledWidth, scaledHeight, true)
            
            val left = (iconSize - scaledWidth) / 2f
            val top = (iconSize - scaledHeight) / 2f
            canvas.drawBitmap(scaledBase, left, top, paint)
            if (scaledBase != baseBitmap) scaledBase.recycle()

            // 3. Apply IconMask
            resources.iconMask?.let { maskName ->
                loadBitmapFromPack(packRes, maskName, iconPackPackage)?.let { mask ->
                    val scaledMask = Bitmap.createScaledBitmap(mask, iconSize, iconSize, true)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    canvas.drawBitmap(scaledMask, 0f, 0f, paint)
                    paint.xfermode = null
                    if (scaledMask != mask) scaledMask.recycle()
                    mask.recycle()
                }
            }

            // 4. Draw IconUpon
            resources.iconUpon?.let { uponName ->
                loadBitmapFromPack(packRes, uponName, iconPackPackage)?.let { upon ->
                    val scaledUpon = Bitmap.createScaledBitmap(upon, iconSize, iconSize, true)
                    canvas.drawBitmap(scaledUpon, 0f, 0f, paint)
                    if (scaledUpon != upon) scaledUpon.recycle()
                    upon.recycle()
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("IconExtractor", "Failed to rasterize custom layers", e)
            val masked = vibes.launcher.angel.util.IconShapeHelper.applyShapeMask(baseBitmap, iconShape)
            if (masked != baseBitmap) {
                baseBitmap.recycle()
            }
            finalBitmap.recycle()
            return masked
        } finally {
            baseBitmap.recycle()
        }

        val masked = vibes.launcher.angel.util.IconShapeHelper.applyShapeMask(finalBitmap, iconShape)
        if (masked != finalBitmap) {
            finalBitmap.recycle()
        }
        return masked
    }

    private fun loadBitmapFromPack(res: android.content.res.Resources, drawableName: String, packPackage: String): Bitmap? {
        val resId = res.getIdentifier(drawableName, "drawable", packPackage)
        if (resId != 0) {
            return BitmapFactory.decodeResource(res, resId)
        }
        return null
    }
}
