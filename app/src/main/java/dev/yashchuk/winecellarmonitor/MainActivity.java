package dev.yashchuk.winecellarmonitor;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private TextView tvCloudStatus;
    private TextView tvTemp, tvHum, tvLight, tvBlindsStatus;
    private SwitchMaterial swCooling, swHumidifier;
    private SeekBar seekBarBlinds;
    private LineChart chartTemp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isUserInteracting = false;

    private Button btnZoomIn, btnZoomOut, btnResetZoom;

    private SwitchMaterial swAutoMode; // Новий світч
    private boolean isAutoMode = false; // Стан автопілота

    private Button btnSettings; // Кнопка налаштувань

    private float limitTempMax = 15.0f;
    private float limitTempMin = 8.0f;
    private float limitHumMin = 60.0f;
    private float limitLightMax = 100.0f;

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
        loadSettings();
        setupChartConfig(); // Налаштування вигляду графіка
        setupListeners();
        startDataPolling();

        monitorCloudConnection();

        tvCloudStatus = findViewById(R.id.tvCloudStatus);

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
        btnSettings = findViewById(R.id.btnSettings);
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

        btnZoomOut.setOnClickListener(v -> {
            // Зменшує масштаб
            chartTemp.zoomOut();
        });

        btnResetZoom.setOnClickListener(v -> {
            chartTemp.fitScreen();
        });

        btnSettings.setOnClickListener(v -> showSettingsDialog());
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            fetchData();
            handler.postDelayed(this, 2000);
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

        firebaseRef.push().setValue(reading)
                .addOnSuccessListener(aVoid -> {
                    Log.d("FIREBASE", "Data sent to cloud");
                })
                .addOnFailureListener(e -> {
                    Log.e("FIREBASE", "Failed to send: " + e.getMessage());
                });
    }

    private void updateChart() {
        List<WineReading> readings = db.wineDao().getLastReadings();

        if (readings.isEmpty()) return;

        Collections.reverse(readings);

        referenceTimestamp = readings.get(0).timestamp;

        List<Entry> entries = new ArrayList<>();
        for (WineReading r : readings) {
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
        if (!isAutoMode) return;

        if (data.sensors.temperature > limitTempMax && !data.actuators.cooling) {
            sendCommand("cooling", true);
            Log.d("AUTO", "High Temp! Cooling ON");
        } else if (data.sensors.temperature < limitTempMin && data.actuators.cooling) {
            sendCommand("cooling", false);
            Log.d("AUTO", "Temp OK. Cooling OFF");
        }

        if (data.sensors.humidity < limitHumMin && !data.actuators.humidifier) {
            sendCommand("humidifier", true);
            Log.d("AUTO", "Low Humidity! Humidifier ON");
        } else if (data.sensors.humidity > (limitHumMin + 5.0) && data.actuators.humidifier) {
            sendCommand("humidifier", false);
            Log.d("AUTO", "Humidity OK. Humidifier OFF");
        }

        if (data.sensors.light > limitLightMax && data.actuators.blindsPosition != 3) {
            sendCommand("blinds_position", 3);
            Log.d("AUTO", "Too bright! Closing blinds");
        } else if (data.sensors.light < (limitLightMax - 20) && data.actuators.blindsPosition == 3) {
            sendCommand("blinds_position", 1);
            Log.d("AUTO", "Dark enough. Opening blinds");
        }
    }

    private void showSettingsDialog() {
        // 1. Створюємо вигляд діалогу з XML
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        // 2. Знаходимо поля введення
        EditText etMaxTemp = dialogView.findViewById(R.id.etMaxTemp);
        EditText etMinTemp = dialogView.findViewById(R.id.etMinTemp);
        EditText etMinHum = dialogView.findViewById(R.id.etMinHum);
        EditText etMaxLight = dialogView.findViewById(R.id.etMaxLight);

        // 3. Заповнюємо поточними значеннями
        etMaxTemp.setText(String.valueOf(limitTempMax));
        etMinTemp.setText(String.valueOf(limitTempMin));
        etMinHum.setText(String.valueOf(limitHumMin));
        etMaxLight.setText(String.valueOf(limitLightMax));

        // 4. Кнопка "Зберегти"
        builder.setTitle("Налаштування Правил")
                .setPositiveButton("Зберегти", (dialog, id) -> {
                    try {
                        // Зчитуємо нові цифри
                        limitTempMax = Float.parseFloat(etMaxTemp.getText().toString());
                        limitTempMin = Float.parseFloat(etMinTemp.getText().toString());
                        limitHumMin = Float.parseFloat(etMinHum.getText().toString());
                        limitLightMax = Float.parseFloat(etMaxLight.getText().toString());

                        saveSettings(); // Зберігаємо в пам'ять
                        Toast.makeText(this, "Налаштування оновлено!", Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Помилка! Введіть коректні числа", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Скасувати", (dialog, id) -> dialog.cancel());

        builder.create().show();
    }

    // Збереження в пам'ять телефону (SharedPreferences)
    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences("WineSettings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("LIMIT_TEMP_MAX", limitTempMax);
        editor.putFloat("LIMIT_TEMP_MIN", limitTempMin);
        editor.putFloat("LIMIT_HUM_MIN", limitHumMin);
        editor.putFloat("LIMIT_LIGHT_MAX", limitLightMax);
        editor.apply();
    }

    // Завантаження з пам'яті
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("WineSettings", MODE_PRIVATE);
        limitTempMax = prefs.getFloat("LIMIT_TEMP_MAX", 15.0f);
        limitTempMin = prefs.getFloat("LIMIT_TEMP_MIN", 8.0f);
        limitHumMin = prefs.getFloat("LIMIT_HUM_MIN", 60.0f);
        limitLightMax = prefs.getFloat("LIMIT_LIGHT_MAX", 100.0f);
    }

    private void monitorCloudConnection() {
        DatabaseReference connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snapshot) {
                boolean connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                if (connected) {
                    tvCloudStatus.setText("Cloud: ● Online");
                    tvCloudStatus.setTextColor(Color.parseColor("#4CAF50")); // Зелений
                } else {
                    tvCloudStatus.setText("Cloud: ● Offline");
                    tvCloudStatus.setTextColor(Color.parseColor("#F44336")); // Червоний
                }
            }

            @Override
            public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError error) {
            }
        });
    }
}