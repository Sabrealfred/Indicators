# ICT Session Sweep + FVG Reversal

Implementación de la estrategia descrita en el audio del video (transcripción en
[`/transcripcion.txt`](../transcripcion.txt) si la guardaste):

> *Despierta a las 9am hora de Nueva York, marca el máximo/mínimo de Asia y
> Londres, espera a que esos altos y bajos sean barridos, baja a 1 minuto, toma
> un Fair Value Gap de reversión hacia el otro lado y apunta a la siguiente
> liquidez (en vez de un RR fijo 1:2). Stop en el swing, objetivo en el próximo
> draw on liquidity.*

Se entrega en dos formatos:

| Carpeta | Qué es |
|---------|--------|
| [`pine/`](pine/) | Indicador **y** estrategia en **Pine Script v5** para TradingView (visual + Strategy Tester + alertas). |
| [`python/`](python/) | Motor de **backtest en Python** (pandas/numpy) con la misma lógica y estadísticas en R. |

## Reglas de la estrategia

1. **Sesiones** (hora local configurable, por defecto NY):
   - Asia: 20:00–00:00 → se guarda su máximo y mínimo.
   - Londres: 02:00–05:00 → se guarda su máximo y mínimo.
2. **Killzone de entrada**: 09:00–12:00 NY. Solo se opera en esta ventana.
3. **Barrido de liquidez (sweep)**: una vela rompe un nivel de sesión y **cierra
   de vuelta** del otro lado.
   - Barrido de un **máximo** → sesgo **bajista** (buscar venta).
   - Barrido de un **mínimo** → sesgo **alcista** (buscar compra).
4. **Entrada**: tras el barrido, primer **Fair Value Gap** de 3 velas en la
   dirección de la reversión.
5. **Stop**: en el extremo del barrido (swing alto/bajo) + buffer opcional.
6. **Objetivo**: la **liquidez opuesta** (máx/mín de sesión del otro lado). Si no
   es válida, se usa un **RR fallback** (2.0 por defecto).
7. Máximo **una operación por día** (configurable).

## Pine Script (TradingView)

1. Abre TradingView → pestaña **Pine Editor**.
2. Pega [`pine/ict_session_sweep_fvg.pine`](pine/ict_session_sweep_fvg.pine).
3. **Add to chart** en un gráfico de **1 minuto**.
4. Ajusta sesiones/timezone en los *inputs*. Usa el **Strategy Tester** para el
   backtest y crea **alertas** con las condiciones `Long setup` / `Short setup`.

Dibuja los niveles de Asia/Londres, marca los sweeps (triángulos) y las cajas de
FVG, además de ejecutar las órdenes.

## Python (backtest)

```bash
cd strategy/python
pip install -r requirements.txt

# Demo inmediata con datos sintéticos (sin red ni datos externos):
python -m ictstrat --days 30 --list

# Con tus propios datos OHLC de 1 minuto:
python -m ictstrat ruta/a/datos_1m.csv --tz America/New_York --list
```

### Formato del CSV

Columnas (mayúsculas/minúsculas indiferentes): una de
`time`/`datetime`/`timestamp`/`date` (ISO 8601 o epoch s/ms) más
`open, high, low, close`. Ejemplo:

```csv
time,open,high,low,close
2024-01-02T09:00:00Z,20010.5,20015.0,20008.0,20012.0
...
```

### Opciones

| Opción | Descripción |
|--------|-------------|
| `--tz` | Timezone de las sesiones (def. `America/New_York`). |
| `--rr` | RR fallback si no hay liquidez opuesta (def. `2.0`). |
| `--no-outer` | Vigila los 4 niveles por separado en vez de la liquidez externa. |
| `--multi-trade` | Permite más de una operación por día. |
| `--days` | Días de datos sintéticos si no pasas CSV. |
| `--list` | Imprime cada operación. |

### Uso como librería

```python
from ictstrat import StrategyConfig, backtest, load_csv
from ictstrat.backtest import compute_stats

df = load_csv("datos_1m.csv")
trades = backtest(df, StrategyConfig(rr_fallback=2.0))
print(compute_stats(trades))
```

## ⚠️ Aviso

- Los **datos sintéticos** incluidos son un *random walk* solo para que el
  backtest corra sin conexión; **no representan un mercado real** ni validan
  ninguna ventaja. Para resultados con sentido usa datos OHLC de 1m reales.
- Esto es material **educativo**, no asesoría financiera. Haz tu propia
  validación antes de arriesgar capital.
