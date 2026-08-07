package vibes.launcher.angel.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AppDao {
    @Query("SELECT * FROM apps")
    List<AppEntity> getAll();

    @Query("SELECT * FROM apps WHERE packageName = :packageName")
    AppEntity getApp(String packageName);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertApp(AppEntity app);

    @Query("DELETE FROM apps WHERE packageName = :packageName")
    void deleteApp(String packageName);
}
