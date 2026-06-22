"""Lógica de la estrategia y motor de backtest (bar a bar).

Réplica de `ict_session_sweep_fvg.pine`. Trabaja sobre un DataFrame OHLC de
1 minuto con índice DatetimeIndex tz-aware.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import pandas as pd

from .config import StrategyConfig


@dataclass
class Trade:
    side: str            # "long" | "short"
    entry_time: pd.Timestamp
    entry: float
    stop: float
    target: float
    exit_time: pd.Timestamp | None = None
    exit: float | None = None
    reason: str | None = None   # "target" | "stop" | "eod"

    @property
    def risk(self) -> float:
        return abs(self.entry - self.stop)

    @property
    def r_multiple(self) -> float:
        """Resultado en múltiplos de R (riesgo inicial)."""
        if self.exit is None or self.risk == 0:
            return 0.0
        pnl = (self.exit - self.entry) if self.side == "long" else (self.entry - self.exit)
        return pnl / self.risk


def _in_window(hour: int, window: tuple[int, int]) -> bool:
    lo, hi = window
    return lo <= hour < hi


@dataclass
class _State:
    asiaH: float = math.nan
    asiaL: float = math.nan
    lonH: float = math.nan
    lonL: float = math.nan
    armed_short: bool = False
    armed_long: bool = False
    sweep_hi: float = math.nan
    sweep_lo: float = math.nan
    opp_level: float = math.nan
    traded_today: bool = False


def backtest(df: pd.DataFrame, cfg: StrategyConfig | None = None) -> list[Trade]:
    """Ejecuta la estrategia sobre `df` y devuelve la lista de operaciones."""
    cfg = cfg or StrategyConfig()
    if df.empty:
        return []

    o = df["open"].to_numpy()
    h = df["high"].to_numpy()
    l = df["low"].to_numpy()
    c = df["close"].to_numpy()
    idx = df.index
    hours = idx.hour.to_numpy()
    dates = idx.normalize()  # fecha local para detectar cambio de día

    st = _State()
    trades: list[Trade] = []
    open_trade: Trade | None = None

    prev_in_asia = prev_in_lon = False
    prev_date = None

    for i in range(len(df)):
        hour = int(hours[i])
        in_asia = _in_window(hour, cfg.asia_session)
        in_lon = _in_window(hour, cfg.london_session)
        in_kill = _in_window(hour, cfg.killzone)

        # --- Gestión de la posición abierta (intrabar con high/low) ---------
        if open_trade is not None:
            hit_stop = (l[i] <= open_trade.stop) if open_trade.side == "long" else (h[i] >= open_trade.stop)
            hit_tgt = (h[i] >= open_trade.target) if open_trade.side == "long" else (l[i] <= open_trade.target)
            if hit_stop and hit_tgt:
                # Ambos en la misma vela: asumimos stop primero (conservador).
                open_trade.exit, open_trade.reason = open_trade.stop, "stop"
            elif hit_stop:
                open_trade.exit, open_trade.reason = open_trade.stop, "stop"
            elif hit_tgt:
                open_trade.exit, open_trade.reason = open_trade.target, "target"
            if open_trade.exit is not None:
                open_trade.exit_time = idx[i]
                trades.append(open_trade)
                open_trade = None

        # --- Reset diario del estado del setup ------------------------------
        if prev_date is not None and dates[i] != prev_date:
            st.armed_short = st.armed_long = False
            st.sweep_hi = st.sweep_lo = math.nan
            st.traded_today = False
        prev_date = dates[i]

        # --- Construcción de máximos/mínimos de sesión ----------------------
        if in_asia and not prev_in_asia:
            st.asiaH, st.asiaL = h[i], l[i]
        elif in_asia:
            st.asiaH, st.asiaL = max(st.asiaH, h[i]), min(st.asiaL, l[i])
        if in_lon and not prev_in_lon:
            st.lonH, st.lonL = h[i], l[i]
        elif in_lon:
            st.lonH, st.lonL = max(st.lonH, h[i]), min(st.lonL, l[i])
        prev_in_asia, prev_in_lon = in_asia, in_lon

        # --- Niveles de liquidez --------------------------------------------
        def _nanmax(a, b):
            if math.isnan(a):
                return b
            if math.isnan(b):
                return a
            return max(a, b)

        def _nanmin(a, b):
            if math.isnan(a):
                return b
            if math.isnan(b):
                return a
            return min(a, b)

        if cfg.use_outer_liquidity:
            buy_levels = [_nanmax(st.asiaH, st.lonH)]
            sell_levels = [_nanmin(st.asiaL, st.lonL)]
        else:
            buy_levels = [st.asiaH, st.lonH]
            sell_levels = [st.asiaL, st.lonL]

        # --- Detección de barrido (solo en killzone) ------------------------
        if in_kill and open_trade is None:
            for lvl in buy_levels:
                if not math.isnan(lvl) and h[i] > lvl and c[i] < lvl and not st.armed_short:
                    st.armed_short, st.armed_long = True, False
                    st.sweep_hi = h[i]
                    st.opp_level = _nanmin(st.asiaL, st.lonL)
            for lvl in sell_levels:
                if not math.isnan(lvl) and l[i] < lvl and c[i] > lvl and not st.armed_long:
                    st.armed_long, st.armed_short = True, False
                    st.sweep_lo = l[i]
                    st.opp_level = _nanmax(st.asiaH, st.lonH)

        # Seguimiento del extremo del barrido (para el stop).
        if st.armed_short:
            st.sweep_hi = h[i] if math.isnan(st.sweep_hi) else max(st.sweep_hi, h[i])
        if st.armed_long:
            st.sweep_lo = l[i] if math.isnan(st.sweep_lo) else min(st.sweep_lo, l[i])

        # --- Fair Value Gap (3 velas) ---------------------------------------
        bull_fvg = i >= 2 and l[i] > h[i - 2]
        bear_fvg = i >= 2 and h[i] < l[i - 2]

        can_trade = in_kill and (not cfg.one_trade_per_day or not st.traded_today)

        # --- Entradas -------------------------------------------------------
        if open_trade is None and can_trade:
            if st.armed_long and bull_fvg:
                stop = (l[i] if math.isnan(st.sweep_lo) else st.sweep_lo) - cfg.stop_buffer
                if not math.isnan(st.opp_level) and st.opp_level > c[i]:
                    target = st.opp_level
                else:
                    target = c[i] + (c[i] - stop) * cfg.rr_fallback
                if stop < c[i]:
                    open_trade = Trade("long", idx[i], c[i], stop, target)
                    st.traded_today, st.armed_long = True, False
            elif st.armed_short and bear_fvg:
                stop = (h[i] if math.isnan(st.sweep_hi) else st.sweep_hi) + cfg.stop_buffer
                if not math.isnan(st.opp_level) and st.opp_level < c[i]:
                    target = st.opp_level
                else:
                    target = c[i] - (stop - c[i]) * cfg.rr_fallback
                if stop > c[i]:
                    open_trade = Trade("short", idx[i], c[i], stop, target)
                    st.traded_today, st.armed_short = True, False

    # Cierre forzado de cualquier posición abierta al final del histórico.
    if open_trade is not None:
        open_trade.exit = c[-1]
        open_trade.exit_time = idx[-1]
        open_trade.reason = "eod"
        trades.append(open_trade)

    return trades
