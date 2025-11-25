package dev.yashchuk.winecellarmonitor;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.yashchuk.winecellarmonitor.database.AppDatabase;
import dev.yashchuk.winecellarmonitor.database.WineReading;
import dev.yashchuk.winecellarmonitor.models.WineData;
import dev.yashchuk.winecellarmonitor.network.RetrofitClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private DatabaseReference firebaseRef;
    private long referenceTimestamp = 0;
    private TextView tvTemp, tvHum, tvLight, tvBlindsStatus;
    private SwitchMaterial swCooling, swHumidifier;
    private SeekBar seekBarBlinds;
    private LineChart chartTemp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isUserInteracting = false;

    private Button btnZoomIn, btnZoomOut, btnResetZoom;

    private SwitchMaterial swAutoMode; // Новий світч
    private boolean isAutoMode = false; // Стан автопілота

    // База даних
    private AppDatabase db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ініціалізація БД
        db = AppDatabase.getDatabase(this);

        firebaseRef = FirebaseDatabase.getInstance().getReference("wine_history");

        initializeViews();
        setupChartConfig(); // Налаштування вигляду графіка
        setupListeners();
        startDataPolling();

    }

    private void initializeViews() {
        tvTemp = findViewById(R.id.tvTemp);
        tvHum = findViewById(R.id.tvHum);
        tvLight = findViewById(R.id.tvLight);
        tvBlindsStatus = findViewById(R.id.tvBlindsStatus);
        swCooling = findViewById(R.id.swCooling);
        swHumidifier = findViewById(R.id.swHumidifier);
        seekBarBlinds = findViewById(R.id.seekBarBlinds);
        chartTemp = findViewById(R.id.chartTemp);
        swAutoMode = findViewById(R.id.swAutoMode);

        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        btnResetZoom = findViewById(R.id.btnResetZoom);
    }

    private void setupChartConfig() {
        chartTemp.getDescription().setEnabled(false);
        chartTemp.setTouchEnabled(true);
        chartTemp.setDragEnabled(true);
        chartTemp.setScaleEnabled(true);
        chartTemp.setPinchZoom(true);
        chartTemp.setDrawGridBackground(false);

        XAxis xAxis = chartTemp.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setLabelCount(4); // Менше підписів, щоб не налізали

        // ВАЖЛИВО: Форматер тепер враховує стартовий час
        xAxis.setValueFormatter(new ValueFormatter() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            @Override
            public String getFormattedValue(float value) {
                // value - це зміщення у мілісекундах (0, 2000, 4000...)
                // додаємо його до referenceTimestamp, щоб отримати реальний час
                return sdf.format(new Date(referenceTimestamp + (long) value));
            }
        });
    }

    private void setupListeners() {
        swCooling.setOnClickListener(v -> sendCommand("cooling", swCooling.isChecked()));
        swHumidifier.setOnClickListener(v -> sendCommand("humidifier", swHumidifier.isChecked()));
        seekBarBlinds.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int position = seekBar.getProgress() + 1;
                sendCommand("blinds_position", position);
                isUserInteracting = false;
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { isUserInteracting = true; }
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBlindsStatus.setText("Позиція: " + (progress + 1));
            }
        });

        swAutoMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isAutoMode = isChecked;

            // Блокуємо ручне керування, якщо включено авто
            swCooling.setEnabled(!isChecked);
            swHumidifier.setEnabled(!isChecked);
            seekBarBlinds.setEnabled(!isChecked);

            String status = isChecked ? "АВТОМАТИКА УВІМКНЕНА" : "РУЧНИЙ РЕЖИМ";
            Toast.makeText(MainActivity.this, status, Toast.LENGTH_SHORT).show();
        });

        btnZoomIn.setOnClickListener(v -> {
            // Збільшує масштаб у 1.4 рази
            chartTemp.zoomIn();
        });

        // Кнопка Мінус (-)
        btnZoomOut.setOnClickListener(v -> {
            // Зменшує масштаб
            chartTemp.zoomOut();
        });

        // Кнопка Скинути (Reset)
        // Повертає графік до початкового стану (щоб побачити всю історію)
        btnResetZoom.setOnClickListener(v -> {
            chartTemp.fitScreen();
        });
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            fetchData();
            handler.postDelayed(this, 2000); // Опитування кожні 2 сек
        }
    };

    private void startDataPolling() {
        handler.post(pollingRunnable);
    }

    private void fetchData() {
        RetrofitClient.getApi().getData().enqueue(new Callback<WineData>() {
            @Override
            public void onResponse(Call<WineData> call, Response<WineData> response) {
                if (response.isSuccessful() && response.body() != null) {
                    WineData data = response.body();
                    updateUI(data);
                    saveToDb(data);

                    // --- ДОДАТИ ЦЕЙ РЯДОК ---
                    checkRules(data);
                }
            }
            @Override
            public void onFailure(Call<WineData> call, Throwable t) {
                Log.e("WINE_ERR", "Error: " + t.getMessage());
            }
        });
    }

    private void saveToDb(WineData data) {
        long now = System.currentTimeMillis();
        WineReading reading = new WineReading(now, data.sensors.temperature, data.sensors.humidity);

        // 1. Збереження локально (Room)
        AppDatabase.databaseWriteExecutor.execute(() -> {
            db.wineDao().insert(reading);
            updateChart();
        });

        // 2. Збереження в хмару (Firebase) - НОВЕ
        // push() створює унікальний ID для запису
        firebaseRef.push().setValue(reading)
                .addOnSuccessListener(aVoid -> {
                    // Можна вивести в лог, що успішно
                    Log.d("FIREBASE", "Data sent to cloud");
                })
                .addOnFailureListener(e -> {
                    Log.e("FIREBASE", "Failed to send: " + e.getMessage());
                });
    }

    private void updateChart() {
        List<WineReading> readings = db.wineDao().getLastReadings();

        if (readings.isEmpty()) return;

        // Перевертаємо, щоб найстаріший був першим (індекс 0)
        Collections.reverse(readings);

        // 1. Запам'ятовуємо час найпершої точки як "нульовий кілометр"
        referenceTimestamp = readings.get(0).timestamp;

        List<Entry> entries = new ArrayList<>();
        for (WineReading r : readings) {
            // 2. Віднімаємо стартовий час від поточного
            // Наприклад: 1732553837000 - 1732553837000 = 0
            // Наступна: 1732553839000 - 1732553837000 = 2000
            float timeOffset = (float) (r.timestamp - referenceTimestamp);

            entries.add(new Entry(timeOffset, (float) r.temperature));
        }

        runOnUiThread(() -> {
            LineDataSet dataSet;
            if (chartTemp.getData() != null && chartTemp.getData().getDataSetCount() > 0) {
                dataSet = (LineDataSet) chartTemp.getData().getDataSetByIndex(0);
                dataSet.setValues(entries);
                chartTemp.getData().notifyDataChanged();
                chartTemp.notifyDataSetChanged();
            } else {
                dataSet = new LineDataSet(entries, "Температура (°C)");
                dataSet.setColor(Color.RED);
                dataSet.setCircleColor(Color.RED);
                dataSet.setLineWidth(2f);
                dataSet.setDrawCircles(false); // Прибрати кружечки на точках, щоб лінія була плавніша
                dataSet.setDrawValues(false);
                dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

                LineData lineData = new LineData(dataSet);
                chartTemp.setData(lineData);
            }
            chartTemp.invalidate();
        });
    }

    private void sendCommand(String device, Object value) {
        Map<String, Object> command = new HashMap<>();
        command.put("device", device);
        command.put("value", value);

        RetrofitClient.getApi().sendCommand(command).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                fetchData();
            }
            @Override
            public void onFailure(Call<Void> call, Throwable t) { }
        });
    }

    private void updateUI(WineData data) {
        tvTemp.setText(String.format(Locale.US, "%.1f°C", data.sensors.temperature));
        tvHum.setText(String.format(Locale.US, "%.1f%%", data.sensors.humidity));
        tvLight.setText(String.format(Locale.US, "%.0f lx", data.sensors.light));

        if (!isUserInteracting) {
            swCooling.setChecked(data.actuators.cooling);
            swHumidifier.setChecked(data.actuators.humidifier);
            int progress = data.actuators.blindsPosition - 1;
            seekBarBlinds.setProgress(progress);
            tvBlindsStatus.setText("Позиція: " + data.actuators.blindsPosition);
        }
    }

    private void checkRules(WineData data) {
        if (!isAutoMode) return; // Якщо автопілот вимкнено - нічого не робимо

        // Правило 1: Температура
        // Якщо > 15 -> Охолодження ON. Якщо впала до норми (< 14) -> OFF (щоб не клацало постійно)
        if (data.sensors.temperature > 15.0 && !data.actuators.cooling) {
            sendCommand("cooling", true);
            Log.d("AUTO", "High Temp! Cooling ON");
        } else if (data.sensors.temperature < 14.0 && data.actuators.cooling) {
            sendCommand("cooling", false);
            Log.d("AUTO", "Temp OK. Cooling OFF");
        }

        // Правило 2: Вологість
        // Якщо < 60% -> Зволожувач ON. Якщо піднялась > 65% -> OFF
        if (data.sensors.humidity < 60.0 && !data.actuators.humidifier) {
            sendCommand("humidifier", true);
            Log.d("AUTO", "Low Humidity! Humidifier ON");
        } else if (data.sensors.humidity > 65.0 && data.actuators.humidifier) {
            sendCommand("humidifier", false);
            Log.d("AUTO", "Humidity OK. Humidifier OFF");
        }

        // Правило 3: Світло
        // Якщо > 100 lx -> Закрити жалюзі (поз. 3). Інакше -> Відкрити (поз. 1)
        if (data.sensors.light > 100 && data.actuators.blindsPosition != 3) {
            sendCommand("blinds_position", 3); // 3 = Закрито
            Log.d("AUTO", "Too bright! Closing blinds");
        } else if (data.sensors.light < 80 && data.actuators.blindsPosition == 3) {
            // Відкриваємо назад, якщо стало темно (гістерезис 80)
            sendCommand("blinds_position", 1); // 1 = Відкрито
            Log.d("AUTO", "Dark enough. Opening blinds");
        }
    }
}