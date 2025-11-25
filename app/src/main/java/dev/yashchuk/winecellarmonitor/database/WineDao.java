package dev.yashchuk.winecellarmonitor.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface WineDao {
    @Insert
    void insert(WineReading reading);

    // Отримати останні 50 записів для графіку
    @Query("SELECT * FROM wine_readings ORDER BY timestamp DESC LIMIT 50")
    List<WineReading> getLastReadings();

    // Очистити все (для тестів)
    @Query("DELETE FROM wine_readings")
    void deleteAll();
}