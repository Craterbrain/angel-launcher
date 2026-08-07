package vibes.launcher.angel.util

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import vibes.launcher.angel.R

object DeviceUtils {

    /**
     * Checks if the device is a tablet based on configuration sw600dp or resource bool.
     */
    fun isTablet(context: Context): Boolean {
        val config = context.resources.configuration
        return context.resources.getBoolean(R.bool.is_tablet) || config.smallestScreenWidthDp >= 600
    }

    /**
     * Applies orientation policy: locks to portrait on phones, permits rotation on tablets.
     */
    fun applyOrientationLock(activity: Activity) {
        if (!isTablet(activity)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
