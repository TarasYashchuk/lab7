from flask import Flask, jsonify, request
import threading
import time
import random

app = Flask(__name__)

# --- СТАН СИСТЕМИ (Початкові дані) ---
state = {
    "sensors": {
        "temperature": 12.0,  # Ідеал 8-14
        "humidity": 65.0,     # Ідеал 60-70
        "light": 50.0         # Ідеал 0-500 (<100 краще)
    },
    "actuators": {
        "cooling": False,       # True/False
        "cooling_power": 1,     # 1 або 2
        "humidifier": False,    # True/False
        "blinds_position": 1    # 1 (відкрито) - 3 (закрито)
    }
}

# --- ЛОГІКА СИМУЛЯЦІЇ (Фізика процесу) ---
def simulation_loop():
    while True:
        # 1. ТЕМПЕРАТУРА
        # Природній нагрів (тепло ззовні)
        state["sensors"]["temperature"] += random.uniform(0.01, 0.05)
        
        # Вплив охолодження
        if state["actuators"]["cooling"]:
            power = state["actuators"]["cooling_power"]
            drop = 0.2 * power  # Чим більша потужність, тим швидше холоне
            state["sensors"]["temperature"] -= drop

        # Шум датчика
        state["sensors"]["temperature"] += random.uniform(-0.1, 0.1)

        # 2. ВОЛОГІСТЬ
        # Природнє висихання
        state["sensors"]["humidity"] -= random.uniform(0.05, 0.1)
        
        # Вплив зволожувача
        if state["actuators"]["humidifier"]:
            state["sensors"]["humidity"] += 0.5

        # Обмеження фізики (не може бути > 100% або < 0%)
        state["sensors"]["humidity"] = max(0, min(100, state["sensors"]["humidity"]))

        # 3. СВІТЛО
        # Базове денне світло (синусоїда або рандом)
        base_light = random.uniform(300, 600) 
        
        # Вплив жалюзі
        # Позиція 1 (відкрито) -> 100% світла
        # Позиція 2 -> 50% світла
        # Позиція 3 (закрито) -> 10% світла
        factor = 1.0
        if state["actuators"]["blinds_position"] == 2: factor = 0.5
        if state["actuators"]["blinds_position"] == 3: factor = 0.1
        
        state["sensors"]["light"] = base_light * factor

        # Затримка симуляції (оновлення раз на секунду)
        time.sleep(1)

# Запуск симуляції в окремому потоці
sim_thread = threading.Thread(target=simulation_loop)
sim_thread.daemon = True
sim_thread.start()

# --- API ENDPOINTS (Для Android) ---

@app.route('/data', methods=['GET'])
def get_data():
    # Повертає поточний стан сенсорів і пристроїв
    return jsonify(state)

@app.route('/control', methods=['POST'])
def control_device():
    # Приймає команди від Android
    # Очікує JSON: {"device": "cooling", "value": true} і т.д.
    data = request.json
    device = data.get('device')
    value = data.get('value')

    if device in state["actuators"]:
        state["actuators"][device] = value
        return jsonify({"status": "success", "new_state": state["actuators"]})
    
    return jsonify({"status": "error", "message": "Device not found"}), 400

if __name__ == '__main__':
    # Запускаємо на всіх інтерфейсах, порт 5000
    print("Запуск сервера на http://0.0.0.0:5000")
    app.run(host='0.0.0.0', port=5000)