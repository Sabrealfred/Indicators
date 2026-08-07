"""ictstrat: backtester de la estrategia ICT 'Session Sweep + FVG Reversal'.

Réplica en Python del indicador Pine `ict_session_sweep_fvg.pine`:
marca máximos/mínimos de Asia y Londres, espera un barrido de liquidez en la
killzone de NY, entra en un Fair Value Gap de reversión, coloca el stop en el
extremo del barrido y apunta a la liquidez opuesta (con fallback de RR fijo).
"""

from .config import StrategyConfig
from .strategy import Trade, backtest
from .data import load_csv, synthetic_ohlc

__all__ = ["StrategyConfig", "Trade", "backtest", "load_csv", "synthetic_ohlc"]
__version__ = "0.1.0"
