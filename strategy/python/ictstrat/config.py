"""Parámetros configurables de la estrategia."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class StrategyConfig:
    # Zona horaria de referencia para las sesiones.
    tz: str = "America/New_York"

    # Ventanas de sesión como (hora_inicio, hora_fin) en hora local `tz`.
    # Asia cruza medianoche (20:00 -> 00:00).
    asia_session: tuple[int, int] = (20, 24)   # 20:00–24:00
    london_session: tuple[int, int] = (2, 5)   # 02:00–05:00
    killzone: tuple[int, int] = (9, 12)        # 09:00–12:00 (entradas)

    # Lógica.
    use_outer_liquidity: bool = True  # nivel = extremo entre Asia y Londres
    one_trade_per_day: bool = True

    # Riesgo / objetivo.
    rr_fallback: float = 2.0          # RR si no hay liquidez opuesta válida
    stop_buffer: float = 0.0          # buffer de precio sobre el extremo del barrido

    def __post_init__(self) -> None:
        for name in ("asia_session", "london_session", "killzone"):
            lo, hi = getattr(self, name)
            if not (0 <= lo <= 24 and 0 < hi <= 24):
                raise ValueError(f"{name} fuera de rango: {(lo, hi)}")
