package dev.yashchuk.winecellarmonitor.models;

import com.google.gson.annotations.SerializedName;

public class Actuators {
    public boolean cooling;

    @SerializedName("cooling_power") // Ім'я в JSON -> ім'я в Java
    public int coolingPower;

    public boolean humidifier;

    @SerializedName("blinds_position")
    public int blindsPosition;
}