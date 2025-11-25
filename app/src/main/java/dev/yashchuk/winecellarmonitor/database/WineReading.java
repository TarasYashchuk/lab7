package dev.yashchuk.winecellarmonitor.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "wine_readings")
public class WineReading {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public long timestamp; // Час запису
    public double temperature;
    public double humidity;

    public WineReading(long timestamp, double temperature, double humidity) {
        this.timestamp = timestamp;
        this.temperature = temperature;
        this.humidity = humidity;
    }
}