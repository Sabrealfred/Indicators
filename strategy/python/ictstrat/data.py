"""Carga de datos OHLC y generador sintético para pruebas/demos."""

from __future__ import annotations

import numpy as np
import pandas as pd


def load_csv(path: str, tz: str = "America/New_York") -> pd.DataFrame:
    """Carga un CSV OHLC de 1 minuto.

    Espera columnas: time/datetime/timestamp, open, high, low, close
    (volume opcional). El índice queda como DatetimeIndex tz-aware en `tz`.
    """
    df = pd.read_csv(path)
    df.columns = [c.strip().lower() for c in df.columns]

    time_col = next(
        (c for c in ("time", "datetime", "timestamp", "date") if c in df.columns),
        None,
    )
    if time_col is None:
        raise ValueError("No se encontró columna de tiempo (time/datetime/timestamp).")

    ts = pd.to_datetime(df[time_col], utc=True, errors="coerce")
    if ts.isna().all():
        # Epoch en segundos o ms.
        raw = pd.to_numeric(df[time_col], errors="coerce")
        unit = "ms" if raw.max() > 1e12 else "s"
        ts = pd.to_datetime(raw, unit=unit, utc=True)

    df.index = ts.dt.tz_convert(tz)
    for col in ("open", "high", "low", "close"):
        if col not in df.columns:
            raise ValueError(f"Falta la columna requerida: {col}")
        df[col] = pd.to_numeric(df[col], errors="coerce")

    df = df[["open", "high", "low", "close"]].dropna().sort_index()
    return df


def synthetic_ohlc(
    days: int = 20,
    tz: str = "America/New_York",
    seed: int = 42,
    start: str = "2024-01-01",
) -> pd.DataFrame:
    """Genera datos OHLC de 1 minuto deterministas (random walk + sesgo de sesión).

    No pretende ser realista; sirve para que el backtest corra sin datos reales.
    """
    rng = np.random.default_rng(seed)
    idx = pd.date_range(start=start, periods=days * 24 * 60, freq="1min", tz=tz)

    # Random walk de precios con un poco de estructura intradía.
    n = len(idx)
    step = rng.normal(0, 0.6, n)
    # Sesgo según hora para crear barridos: empuje al alza en Asia/Londres,
    # reversiones en NY.
    hours = idx.hour.to_numpy()
    bias = np.where(np.isin(hours, [20, 21, 2, 3]), 0.15, 0.0)
    bias += np.where(np.isin(hours, [9, 10, 11]), -0.10, 0.0)
    price = 20000 + np.cumsum(step + bias)

    close = price
    open_ = np.empty(n)
    open_[0] = close[0]
    open_[1:] = close[:-1]
    noise = np.abs(rng.normal(0, 0.8, n))
    high = np.maximum(open_, close) + noise
    low = np.minimum(open_, close) - noise

    return pd.DataFrame(
        {"open": open_, "high": high, "low": low, "close": close}, index=idx
    )
