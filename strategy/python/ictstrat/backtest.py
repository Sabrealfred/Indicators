"""Estadísticas de rendimiento a partir de una lista de operaciones."""

from __future__ import annotations

from dataclasses import dataclass

from .strategy import Trade


@dataclass
class Stats:
    n_trades: int
    wins: int
    losses: int
    win_rate: float
    total_r: float
    avg_r: float
    expectancy: float
    max_drawdown_r: float
    profit_factor: float

    def __str__(self) -> str:
        return (
            f"Operaciones:      {self.n_trades}\n"
            f"Ganadoras:        {self.wins}  ({self.win_rate:.1%})\n"
            f"Perdedoras:       {self.losses}\n"
            f"R total:          {self.total_r:+.2f}R\n"
            f"R medio/op:       {self.avg_r:+.2f}R\n"
            f"Expectancy:       {self.expectancy:+.2f}R\n"
            f"Profit factor:    {self.profit_factor:.2f}\n"
            f"Max drawdown:     {self.max_drawdown_r:.2f}R"
        )


def compute_stats(trades: list[Trade]) -> Stats:
    n = len(trades)
    if n == 0:
        return Stats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    rs = [t.r_multiple for t in trades]
    wins = sum(1 for r in rs if r > 0)
    losses = sum(1 for r in rs if r <= 0)
    total_r = sum(rs)
    gross_win = sum(r for r in rs if r > 0)
    gross_loss = -sum(r for r in rs if r < 0)

    # Drawdown sobre la curva acumulada de R.
    equity = 0.0
    peak = 0.0
    max_dd = 0.0
    for r in rs:
        equity += r
        peak = max(peak, equity)
        max_dd = min(max_dd, equity - peak)

    return Stats(
        n_trades=n,
        wins=wins,
        losses=losses,
        win_rate=wins / n,
        total_r=total_r,
        avg_r=total_r / n,
        expectancy=total_r / n,
        max_drawdown_r=max_dd,
        profit_factor=(gross_win / gross_loss) if gross_loss > 0 else float("inf"),
    )
