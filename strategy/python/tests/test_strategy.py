"""Pruebas del backtester ICT."""

import math

import pandas as pd
import pytest

from ictstrat.backtest import compute_stats
from ictstrat.config import StrategyConfig
from ictstrat.data import synthetic_ohlc
from ictstrat.strategy import Trade, backtest


def test_synthetic_runs():
    df = synthetic_ohlc(days=10, seed=1)
    assert len(df) == 10 * 24 * 60
    assert list(df.columns) == ["open", "high", "low", "close"]
    assert (df["high"] >= df["low"]).all()


def test_backtest_returns_trades():
    df = synthetic_ohlc(days=20, seed=7)
    trades = backtest(df, StrategyConfig())
    assert isinstance(trades, list)
    for t in trades:
        assert t.side in ("long", "short")
        assert t.exit is not None
        # El stop está del lado correcto de la entrada.
        if t.side == "long":
            assert t.stop < t.entry
        else:
            assert t.stop > t.entry


def test_r_multiple_sign():
    t = Trade("long", pd.Timestamp("2024-01-01"), entry=100, stop=90, target=120)
    t.exit = 120
    assert math.isclose(t.r_multiple, 2.0)
    t.exit = 90
    assert math.isclose(t.r_multiple, -1.0)

    s = Trade("short", pd.Timestamp("2024-01-01"), entry=100, stop=110, target=80)
    s.exit = 80
    assert math.isclose(s.r_multiple, 2.0)


def test_one_trade_per_day():
    df = synthetic_ohlc(days=15, seed=3)
    trades = backtest(df, StrategyConfig(one_trade_per_day=True))
    days = [t.entry_time.normalize() for t in trades]
    assert len(days) == len(set(days)), "No debe haber más de una entrada por día"


def test_stats_empty():
    s = compute_stats([])
    assert s.n_trades == 0
    assert s.total_r == 0.0


def test_stats_basic():
    df = synthetic_ohlc(days=20, seed=11)
    stats = compute_stats(backtest(df))
    assert stats.n_trades >= 0
    assert 0.0 <= stats.win_rate <= 1.0
