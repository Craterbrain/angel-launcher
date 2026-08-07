package vibes.launcher.angel.ui

import android.content.Context
import android.content.Intent
import org.xmlpull.v1.XmlPullParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IconPack(val packageName: String, val label: String)

data class IconPackItem(
    val component: String,
    val packageName: String,
    val drawableName: String
)

data class IconPackResources(
    val appFilterMap: HashMap<String, String> = HashMap(),
    val allItems: List<IconPackItem> = emptyList(),
    val iconBacks: List<String> = emptyList(),
    val iconMask: String? = null,
    val iconUpon: String? = null,
    val iconScale: Float = 1.0f,
    val categories: Map<String, List<String>> = emptyMap(),
    val allDrawables: List<String> = emptyList()
)

class IconPackParser {

    companion object {
        val INSTANCE = IconPackParser()
        val STANDARD_CATEGORIES = listOf(
            "All",
            "Current App",
            "Browser",
            "Camera",
            "Calendar",
            "Clock",
            "Communication",
            "Files",
            "Gallery",
            "Games",
            "Mail",
            "Maps",
            "Music",
            "Settings",
            "Social",
            "Shopping",
            "Weather"
        )

        private val COMPONENT_REGEX = Regex("ComponentInfo\\{([^/]+)/.*\\}")
        private val SANITIZE_REGEX = Regex("[^a-z0-9]")

        val CATEGORY_KEYWORDS = mapOf(
            "Browser" to listOf("browser", "chrome", "firefox", "opera", "edge", "web", "safari", "brave", "duckduckgo", "internet"),
            "Camera" to listOf("camera", "cam", "photo", "lens", "gcam", "recorder", "record"),
            "Calendar" to listOf("calendar", "cal", "event", "schedule", "date"),
            "Clock" to listOf("clock", "alarm", "timer", "stopwatch", "time", "watch"),
            "Communication" to listOf("call", "dialer", "phone", "sms", "message", "messenger", "chat", "telegram", "whatsapp", "signal", "discord", "wechat", "viber"),
            "Files" to listOf("file", "folder", "storage", "explorer", "download", "zip", "archive", "cloud", "drive", "dropbox"),
            "Gallery" to listOf("gallery", "photos", "image", "pic", "video", "player", "media", "vlc", "movie", "film", "stream"),
            "Games" to listOf("game", "play", "arcade", "steam", "nintendo", "xbox", "playstation", "dice", "cards", "rpg"),
            "Mail" to listOf("mail", "email", "gmail", "outlook", "inbox", "yahoo", "letter", "spark", "thunderbird"),
            "Maps" to listOf("map", "maps", "navigation", "gps", "earth", "waze", "travel", "uber", "lyft", "location", "transit"),
            "Music" to listOf("music", "audio", "sound", "spotify", "podcast", "radio", "song", "track", "deezer", "tidal", "apple_music"),
            "Settings" to listOf("setting", "settings", "gear", "pref", "tool", "cleaner", "terminal", "config", "system", "tweak", "manager"),
            "Social" to listOf("social", "facebook", "twitter", "instagram", "reddit", "tiktok", "snapchat", "threads", "bluesky", "mastodon", "pinterest", "linkedin"),
            "Shopping" to listOf("shop", "store", "amazon", "ebay", "market", "cart", "wallet", "pay", "bank", "finance", "money", "crypto"),
            "Weather" to listOf("weather", "sun", "cloud", "rain", "forecast", "storm", "snow", "temp")
        )
    }

    fun getAvailableIconPacks(context: Context): List<IconPack> {
        val pm = context.packageManager
        val themes = mutableListOf<IconPack>()
        val intent = Intent("com.novalauncher.THEME")
        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        for (ri in resolveInfos) {
            val packageName = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            themes.add(IconPack(packageName, label))
        }
        return themes
    }


    suspend fun parseAppFilter(context: Context, iconPackPackageName: String?): IconPackResources = withContext(Dispatchers.IO) {
        if (iconPackPackageName.isNullOrEmpty()) {
            return@withContext IconPackResources()
        }

        val map = HashMap<String, String>(512)
        val allItems = mutableListOf<IconPackItem>()
        val drawablesSet = LinkedHashSet<String>()
        val backs = mutableListOf<String>()
        var mask: String? = null
        var upon: String? = null
        var scale = 1.0f
        val customCategories = mutableMapOf<String, MutableList<String>>()

        try {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(iconPackPackageName)
            
            // 1. Parse appfilter.xml
            val appFilterId = res.getIdentifier("appfilter", "xml", iconPackPackageName)
            if (appFilterId != 0) {
                res.getXml(appFilterId).use { parser ->
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "item" -> {
                                    val component = parser.getAttributeValue(null, "component")
                                    val drawable = parser.getAttributeValue(null, "drawable")
                                    if (component != null && drawable != null) {
                                        val matchResult = COMPONENT_REGEX.find(component)
                                        val pkg = matchResult?.groups?.get(1)?.value ?: component
                                        map[pkg] = drawable
                                        allItems.add(IconPackItem(component, pkg, drawable))
                                        drawablesSet.add(drawable)
                                    }
                                }
                                "iconback" -> {
                                    for (i in 0 until parser.attributeCount) {
                                        if (parser.getAttributeName(i).startsWith("img")) {
                                            val back = parser.getAttributeValue(i)
                                            backs.add(back)
                                            drawablesSet.add(back)
                                        }
                                    }
                                }
                                "iconmask" -> {
                                    mask = parser.getAttributeValue(null, "img1")
                                    mask?.let { drawablesSet.add(it) }
                                }
                                "iconupon" -> {
                                    upon = parser.getAttributeValue(null, "img1")
                                    upon?.let { drawablesSet.add(it) }
                                }
                                "scale" -> {
                                    val factor = parser.getAttributeValue(null, "factor")
                                    if (factor != null) {
                                        scale = factor.toFloatOrNull() ?: 1.0f
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            }

            // 2. Parse drawable.xml if available
            val drawableXmlId = res.getIdentifier("drawable", "xml", iconPackPackageName)
            if (drawableXmlId != 0) {
                try {
                    res.getXml(drawableXmlId).use { parser ->
                        var eventType = parser.eventType
                        var currentCategory = "General"
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "category" -> {
                                        currentCategory = parser.getAttributeValue(null, "title") ?: "General"
                                    }
                                    "item" -> {
                                        val drawable = parser.getAttributeValue(null, "drawable")
                                        if (drawable != null) {
                                            drawablesSet.add(drawable)
                                            customCategories.getOrPut(currentCategory) { mutableListOf() }.add(drawable)
                                        }
                                    }
                                }
                            }
                            eventType = parser.next()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        val sortedDrawables = drawablesSet.sorted()
        return@withContext IconPackResources(
            appFilterMap = map,
            allItems = allItems,
            iconBacks = backs,
            iconMask = mask,
            iconUpon = upon,
            iconScale = scale,
            categories = customCategories,
            allDrawables = sortedDrawables
        )
    }

    fun findAppVariants(
        packageName: String,
        label: String,
        resources: IconPackResources?
    ): List<String> {
        if (resources == null) return emptyList()

        val defaultDrawable = resources.appFilterMap[packageName]
        val results = LinkedHashSet<String>()

        // 1. All explicitly mapped items for this package
        for (item in resources.allItems) {
            if (item.packageName == packageName && item.drawableName != defaultDrawable) {
                results.add(item.drawableName)
            }
        }

        // 2. Keyword searches
        val cleanLabel = label.lowercase().replace(SANITIZE_REGEX, "")
        val lastPkgSegment = packageName.substringAfterLast(".").lowercase()
        val keywords = listOf(cleanLabel, lastPkgSegment).filter { it.length >= 3 }

        for (drawable in resources.allDrawables) {
            if (drawable == defaultDrawable) continue
            val lower = drawable.lowercase()
            for (kw in keywords) {
                if (lower.contains(kw)) {
                    results.add(drawable)
                    break
                }
            }
        }

        return results.toList()
    }

    fun getDrawablesForCategory(
        category: String,
        packageName: String?,
        label: String?,
        resources: IconPackResources?
    ): List<String> {
        if (resources == null) return emptyList()

        return when (category) {
            "All" -> resources.allDrawables
            "Current App" -> {
                if (packageName == null || label == null) emptyList()
                else {
                    val list = mutableListOf<String>()
                    resources.appFilterMap[packageName]?.let { list.add(it) }
                    list.addAll(findAppVariants(packageName, label, resources))
                    list.distinct()
                }
            }
            else -> {
                val keywords = CATEGORY_KEYWORDS[category] ?: listOf(category.lowercase())
                resources.allDrawables.filter { drawable ->
                    val lower = drawable.lowercase()
                    keywords.any { kw -> lower.contains(kw) }
                }
            }
        }
    }
}
