package vibes.launcher.angel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import vibes.launcher.angel.R
import vibes.launcher.angel.data.AppListItem
import vibes.launcher.angel.data.AppModel
import vibes.launcher.angel.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListAdapter(
    var items: List<AppListItem>,
    private val scope: CoroutineScope,
    private val iconCacheManager: IconCacheManager,
    private val iconExtractor: IconExtractor,
    var iconPackPackage: String?,
    var iconPackResources: IconPackResources?,
    private val settingsManager: SettingsManager,
    private val onAppClick: (AppModel) -> Unit,
    private val onAppLongClick: (AppModel, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_APP = 1
        const val VIEW_TYPE_DIVIDER = 2

        fun getBaseUnit(displayMetrics: android.util.DisplayMetrics, scale: Float): Int {
            return (displayMetrics.widthPixels * 0.12f * scale).toInt()
        }
    }

    // Cache scale values — refreshed when settings change (onResume) to avoid SP reads per ViewHolder
    var cachedGlobalScale: Float = settingsManager.globalScale
    var cachedIconScale: Float = settingsManager.iconScale
    var cachedTextScale: Float = settingsManager.textScale
    var cachedIconShape: Int = settingsManager.iconShape

    var isEditMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var onStartDragListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    // Cached colors — computed once per updateItems() to avoid SP reads per row
    private var cachedTextColor: Int = android.graphics.Color.WHITE
    private var cachedDividerColor: Int = android.graphics.Color.argb(64, 255, 255, 255)

    fun refreshSettings() {
        cachedGlobalScale = settingsManager.globalScale
        cachedIconScale = settingsManager.iconScale
        cachedTextScale = settingsManager.textScale
        cachedIconShape = settingsManager.iconShape
        refreshCachedColors()
    }

    private fun refreshCachedColors() {
        cachedTextColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.appTextColor,
            (settingsManager.appTextOpacity.coerceIn(0f, 1f) * 255).toInt()
        )
        cachedDividerColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.appTextColor,
            (settingsManager.appTextOpacity.coerceIn(0f, 1f) * 0.25f * 255).toInt()
        )
    }

    fun updateItems(newItems: List<AppListItem>, useDiff: Boolean = false) {
        refreshCachedColors()
        if (!useDiff) {
            items = newItems
            notifyDataSetChanged()
            return
        }
        val oldItems = items
        scope.launch(Dispatchers.Default) {
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldItems.getOrNull(oldItemPosition) ?: return false
                    val newItem = newItems.getOrNull(newItemPosition) ?: return false
                    return when {
                        oldItem is AppListItem.Header && newItem is AppListItem.Header -> oldItem.title == newItem.title
                        oldItem is AppListItem.App && newItem is AppListItem.App ->
                            oldItem.appModel.packageName == newItem.appModel.packageName &&
                            oldItem.appModel.isFavoriteSection == newItem.appModel.isFavoriteSection
                        oldItem is AppListItem.Divider && newItem is AppListItem.Divider -> true
                        else -> false
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldVal = oldItems.getOrNull(oldItemPosition) ?: return false
                    val newVal = newItems.getOrNull(newItemPosition) ?: return false
                    return oldVal == newVal
                }
            })
            withContext(Dispatchers.Main) {
                if (items === oldItems) {
                    items = newItems
                    diffResult.dispatchUpdatesTo(this@AppListAdapter)
                }
            }
        }
    }

    fun setItemsImmediate(newItems: List<AppListItem>) {
        updateItems(newItems, useDiff = false)
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < 0 || fromPosition >= items.size || toPosition < 0 || toPosition >= items.size) {
            return false
        }
        val mutable = items.toMutableList()
        val item = mutable.removeAt(fromPosition)
        mutable.add(toPosition, item)
        items = mutable
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AppListItem.Header -> VIEW_TYPE_HEADER
            is AppListItem.App -> VIEW_TYPE_APP
            is AppListItem.Divider -> VIEW_TYPE_DIVIDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val dm = parent.resources.displayMetrics
        val baseU = (dm.widthPixels * 0.12f * cachedGlobalScale).toInt()
        
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
                view.setPadding((baseU * 0.5f).toInt(), (baseU * 1.0f).toInt(), (baseU * 0.5f).toInt(), (baseU * 0.3f).toInt())
                HeaderViewHolder(view)
            }
            VIEW_TYPE_DIVIDER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_divider, parent, false)
                view.setPadding((baseU * 0.5f).toInt(), (baseU * 0.25f).toInt(), (baseU * 0.5f).toInt(), (baseU * 0.25f).toInt())
                DividerViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
                view.setPadding((baseU * 0.5f).toInt(), (baseU * 0.2f).toInt(), (baseU * 0.5f).toInt(), (baseU * 0.2f).toInt())
                
                val appIcon = view.findViewById<ImageView>(R.id.appIcon)
                val lp = appIcon.layoutParams
                lp.width = (baseU * cachedIconScale).toInt()
                lp.height = (baseU * cachedIconScale).toInt()
                appIcon.layoutParams = lp
                
                val appName = view.findViewById<TextView>(R.id.appName)
                val nameLp = appName.layoutParams as android.widget.LinearLayout.LayoutParams
                nameLp.marginStart = (baseU * 0.5f).toInt()
                appName.layoutParams = nameLp
                
                appName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseU * 0.35f * cachedTextScale)
                
                AppViewHolder(view, lp.width)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when {
            holder is HeaderViewHolder && item is AppListItem.Header -> holder.bind(item)
            holder is AppViewHolder && item is AppListItem.App -> holder.bind(item.appModel)
            holder is DividerViewHolder && item is AppListItem.Divider -> holder.bind()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is AppViewHolder) {
            holder.cancelJob()
        }
    }

    override fun getItemCount(): Int = items.size

    inner class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dividerLine: View = itemView.findViewById(R.id.dividerLine)
        
        fun bind() {
            dividerLine.setBackgroundColor(cachedDividerColor)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerText: TextView = itemView.findViewById(R.id.headerText)
        
        fun bind(header: AppListItem.Header) {
            if (header.title.isEmpty()) { // Spacer
                itemView.visibility = View.INVISIBLE
                itemView.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    (32 * itemView.resources.displayMetrics.density).toInt()
                )
            } else {
                itemView.visibility = View.VISIBLE
                itemView.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                headerText.text = header.title
                headerText.setTextColor(cachedTextColor)
            }
        }
    }

    inner class AppViewHolder(itemView: View, private val targetIconSize: Int) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        private val appName: TextView = itemView.findViewById(R.id.appName)
        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        private var currentJob: Job? = null
        private var boundPackageName: String? = null

        fun cancelJob() {
            currentJob?.cancel()
            currentJob = null
            boundPackageName = null
        }

        fun bind(app: AppModel) {
            cancelJob()
            boundPackageName = app.packageName
            
            if (app.packageName == "vibes.launcher.angel.spacer") {
                itemView.visibility = android.view.View.INVISIBLE
                itemView.layoutParams = RecyclerView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                    (32 * itemView.resources.displayMetrics.density).toInt()
                )
                return
            } else {
                itemView.visibility = android.view.View.VISIBLE
                itemView.layoutParams = RecyclerView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            appName.text = app.displayLabel
            appName.setTextColor(cachedTextColor)
            vibes.launcher.angel.util.IconShapeHelper.applyShapeToView(appIcon, cachedIconShape)
            
            val expectedPackage = app.packageName

            if (app.packageName == "vibes.launcher.angel.settings") {
                val cacheKey = "angel_settings_icon_shape${cachedIconShape}_size${targetIconSize}"
                val memCached = iconCacheManager.getFromMemory(cacheKey)
                if (memCached != null) {
                    appIcon.setImageBitmap(memCached)
                } else {
                    appIcon.setImageDrawable(null)
                    currentJob = scope.launch(Dispatchers.Main) {
                        val bitmap = withContext(Dispatchers.IO) {
                            var diskCached = iconCacheManager.getFromDisk(cacheKey)
                            if (diskCached == null) {
                                diskCached = iconExtractor.loadBuiltinDrawable(
                                    itemView.context,
                                    R.drawable.ic_settings_app,
                                    targetIconSize,
                                    cachedIconShape
                                )
                                if (diskCached != null) {
                                    iconCacheManager.put(cacheKey, diskCached)
                                }
                            }
                            diskCached
                        }
                        if (boundPackageName == expectedPackage) {
                            if (bitmap != null) {
                                appIcon.setImageBitmap(bitmap)
                            } else {
                                appIcon.setImageResource(R.drawable.ic_settings_app)
                            }
                        }
                    }
                }
            } else {
                val customKeyPart = if (app.customIconDrawable != null) {
                    "custom_${app.customIconPack}_${app.customIconDrawable}"
                } else if (app.useDefaultIcon) {
                    "default"
                } else {
                    iconPackPackage ?: "default"
                }
                val cacheKey = "${app.packageName}_${customKeyPart}_shape${cachedIconShape}_size${targetIconSize}"
                
                // Tier 1 Memory cache hit (fastest)
                val memCached = iconCacheManager.getFromMemory(cacheKey)
                if (memCached != null) {
                    appIcon.setImageBitmap(memCached)
                } else {
                    appIcon.setImageDrawable(null)

                    // Background Job for Tier 2 Disk Cache or Extraction
                    currentJob = scope.launch(Dispatchers.Main) {
                        val bitmap = iconCacheManager.getOrExtract(cacheKey) {
                            iconExtractor.extractAndRasterizeIcon(
                                itemView.context,
                                app.packageName,
                                iconPackPackage,
                                iconPackResources,
                                targetIconSize,
                                app.useDefaultIcon,
                                app.customIconPack,
                                app.customIconDrawable,
                                cachedIconShape
                            )
                        }
                        
                        if (boundPackageName == expectedPackage && bitmap != null) {
                            appIcon.setImageBitmap(bitmap)
                        }
                    }
                }
            }

            if (isEditMode) {
                dragHandle.visibility = View.VISIBLE
                dragHandle.setColorFilter(cachedTextColor)
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        onStartDragListener?.invoke(this)
                        true
                    } else {
                        false
                    }
                }
            } else {
                dragHandle.visibility = View.GONE
                dragHandle.setOnTouchListener(null)
            }

            itemView.setOnClickListener {
                if (!isEditMode) {
                    onAppClick(app)
                }
            }
            itemView.setOnLongClickListener {
                if (!isEditMode) {
                    onAppLongClick(app, it)
                    true
                } else {
                    onStartDragListener?.invoke(this)
                    true
                }
            }
        }
    }
}
