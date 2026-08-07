package vibes.launcher.angel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackParserTest {

    private val parser = IconPackParser()

    @Test
    fun testFindAppVariants() {
        val appFilterMap = HashMap<String, String>().apply {
            put("com.google.android.youtube", "youtube")
            put("com.google.android.apps.photos", "photos")
        }
        val allDrawables = listOf(
            "youtube",
            "youtube_music",
            "youtube_red",
            "youtube_alt",
            "photos",
            "photos_alt",
            "camera",
            "browser"
        )
        val resources = IconPackResources(
            appFilterMap = appFilterMap,
            allDrawables = allDrawables
        )

        val variants = parser.findAppVariants("com.google.android.youtube", "YouTube", resources)
        assertEquals(3, variants.size)
        assertTrue(variants.contains("youtube_music"))
        assertTrue(variants.contains("youtube_red"))
        assertTrue(variants.contains("youtube_alt"))
    }

    @Test
    fun testGetDrawablesForCategory() {
        val appFilterMap = HashMap<String, String>().apply {
            put("com.android.chrome", "chrome")
        }
        val resources = IconPackResources(
            appFilterMap = appFilterMap,
            allDrawables = listOf(
                "chrome",
                "firefox",
                "browser_default",
                "camera_lens",
                "gallery_photo",
                "calculator"
            )
        )

        val browserDrawables = parser.getDrawablesForCategory("Browser", "com.android.chrome", "Chrome", resources)
        assertTrue(browserDrawables.contains("chrome"))
        assertTrue(browserDrawables.contains("firefox"))
        assertTrue(browserDrawables.contains("browser_default"))
    }
}
