"""Interfaz de línea de comandos para audio_extractor."""

from __future__ import annotations

import argparse
import sys

from . import __version__
from .core import AUDIO_CODECS, AudioExtractionError, extract, is_url


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="audio-extractor",
        description=(
            "Extrae audio desde una URL (TikTok, YouTube, etc.) "
            "o desde un archivo de video local."
        ),
    )
    parser.add_argument(
        "source",
        help="URL del video o ruta a un archivo de video local.",
    )
    parser.add_argument(
        "-o", "--output",
        help=(
            "Archivo de salida (para archivos locales) o carpeta de destino "
            "(para URLs). Por defecto: junto al original / carpeta actual."
        ),
    )
    parser.add_argument(
        "-f", "--format",
        dest="audio_format",
        default="mp3",
        choices=sorted(AUDIO_CODECS),
        help="Formato de audio de salida (por defecto: mp3).",
    )
    parser.add_argument(
        "-b", "--bitrate",
        default="192k",
        help="Bitrate de audio, p. ej. 128k, 192k, 320k (por defecto: 192k).",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Sobrescribe el archivo de salida si ya existe (solo archivos locales).",
    )
    parser.add_argument(
        "-V", "--version",
        action="version",
        version=f"%(prog)s {__version__}",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    kind = "URL" if is_url(args.source) else "archivo local"
    print(f"→ Procesando {kind}: {args.source}", file=sys.stderr)

    try:
        result = extract(
            args.source,
            output=args.output,
            audio_format=args.audio_format,
            bitrate=args.bitrate,
            overwrite=args.overwrite,
        )
    except AudioExtractionError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:  # pragma: no cover
        print("\nCancelado.", file=sys.stderr)
        return 130

    print(f"✓ Audio extraído: {result}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
