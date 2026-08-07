package vibes.launcher.angel.data

sealed class AppListItem {
    data class Header(val title: String) : AppListItem()
    data class App(val appModel: AppModel) : AppListItem()
    object Divider : AppListItem()
}
