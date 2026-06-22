"""CLI: corre el backtest sobre un CSV o sobre datos sintéticos."""

from __future__ import annotations

import argparse
import sys

from .backtest import compute_stats
from .config import StrategyConfig
from .data import load_csv, synthetic_ohlc
from .strategy import backtest


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="ictstrat",
        description="Backtest de la estrategia ICT Session Sweep + FVG Reversal.",
    )
    p.add_argument("csv", nargs="?", help="CSV OHLC de 1m. Si se omite, usa datos sintéticos.")
    p.add_argument("--tz", default="America/New_York", help="Timezone de las sesiones.")
    p.add_argument("--rr", type=float, default=2.0, help="RR fallback (por defecto 2.0).")
    p.add_argument("--no-outer", action="store_true",
                   help="Vigila los 4 niveles por separado en vez de la liquidez externa.")
    p.add_argument("--multi-trade", action="store_true",
                   help="Permite más de una operación por día.")
    p.add_argument("--days", type=int, default=20,
                   help="Días de datos sintéticos (si no se pasa CSV).")
    p.add_argument("--list", action="store_true", help="Imprime cada operación.")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    cfg = StrategyConfig(
        tz=args.tz,
        rr_fallback=args.rr,
        use_outer_liquidity=not args.no_outer,
        one_trade_per_day=not args.multi_trade,
    )

    if args.csv:
        print(f"→ Cargando {args.csv}", file=sys.stderr)
        df = load_csv(args.csv, tz=args.tz)
    else:
        print(f"→ Sin CSV: generando {args.days} días sintéticos", file=sys.stderr)
        df = synthetic_ohlc(days=args.days, tz=args.tz)

    trades = backtest(df, cfg)
    stats = compute_stats(trades)

    if args.list:
        for t in trades:
            print(f"{t.entry_time}  {t.side:5s}  in={t.entry:.2f} "
                  f"stop={t.stop:.2f} tgt={t.target:.2f}  "
                  f"out={t.exit:.2f} ({t.reason})  {t.r_multiple:+.2f}R")
        print("-" * 60)

    print(stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
