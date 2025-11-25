package dev.yashchuk.winecellarmonitor.network;

import dev.yashchuk.winecellarmonitor.models.WineData;
import java.util.Map;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface WineApi {

    // Отримати всі дані
    @GET("/data")
    Call<WineData> getData();

    // Відправити команду (наприклад, включити охолодження)
    // Ми будемо слати Map, наприклад: {"device": "cooling", "value": true}
    @POST("/control")
    Call<Void> sendCommand(@Body Map<String, Object> command);
}
