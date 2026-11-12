package vibes.launcher.angel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibes.launcher.angel.data.AppModel

class IconCacheKeyTest {

    private fun app(lastUpdateTime: Long = 1L) = AppModel(
        label = "Foo",
        packageName = "com.foo",
        isHidden = false,
        isPinned = false,
        activityName = "com.foo.MainActivity",
        lastUpdateTime = lastUpdateTime
    )

    @Test
    fun reloadedAppsCompareEqual() {
        // DiffUtil relies on this: two loads of an unchanged app must not count as a change
        assertEquals(app(), app())
    }

    @Test
    fun updatedAppGetsNewKey() {
        assertNotEquals(
            IconCacheManager.appIconKey(app(lastUpdateTime = 1L), null, 0, 96),
            IconCacheManager.appIconKey(app(lastUpdateTime = 2L), null, 0, 96)
        )
    }

    @Test
    fun keyStartsWithPackageSoItCanBeInvalidatedAlone() {
        val key = IconCacheManager.appIconKey(app(), "com.pack", 1, 96)
        assertTrue(key.startsWith("com.foo|"))
        // A package whose name extends this one must not share the prefix
        assertTrue(!key.startsWith("com.foo_bar|"))
    }

    @Test
    fun customIconAndPackAreDistinct() {
        val custom = app().copy(customIconPack = "com.pack", customIconDrawable = "foo")
        assertNotEquals(
            IconCacheManager.appIconKey(app(), "com.pack", 1, 96),
            IconCacheManager.appIconKey(custom, "com.pack", 1, 96)
        )
    }
}
