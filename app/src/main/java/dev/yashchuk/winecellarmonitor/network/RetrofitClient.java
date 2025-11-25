package dev.yashchuk.winecellarmonitor.network;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    // ВАЖЛИВО: Для емулятора адреса localhost комп'ютера це завжди 10.0.2.2
    private static final String BASE_URL = "http://10.0.2.2:5000/";

    private static Retrofit retrofit = null;

    public static WineApi getApi() {
        if (retrofit == null) {
            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit.create(WineApi.class);
    }
}