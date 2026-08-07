package vibes.launcher.angel.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "apps")
public class AppEntity {
    @PrimaryKey
    @NonNull
    public String packageName;
    
    public boolean isHidden;
    public boolean isPinned;
    public boolean useDefaultIcon;
    
    @Nullable
    public String customIconPack;
    
    @Nullable
    public String customIconDrawable;

    @Nullable
    public String customLabel;

    public AppEntity(@NonNull String packageName, boolean isHidden, boolean isPinned, boolean useDefaultIcon, @Nullable String customIconPack, @Nullable String customIconDrawable, @Nullable String customLabel) {
        this.packageName = packageName;
        this.isHidden = isHidden;
        this.isPinned = isPinned;
        this.useDefaultIcon = useDefaultIcon;
        this.customIconPack = customIconPack;
        this.customIconDrawable = customIconDrawable;
        this.customLabel = customLabel;
    }
}
