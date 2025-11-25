package dev.yashchuk.winecellarmonitor.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {WineReading.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract WineDao wineDao();

    private static volatile AppDatabase INSTANCE;

    // Екзекутор для виконання запитів у фоні (щоб не блокувати UI)
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(4);

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "wine_database")
                            .fallbackToDestructiveMigration() // Якщо змінимо структуру - просто очистити
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}