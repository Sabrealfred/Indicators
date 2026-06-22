"""Lógica de extracción de audio.

Dos rutas:
- URL remota (TikTok, YouTube, etc.): se descarga con yt-dlp y se extrae el audio.
- Archivo de video local: se extrae la pista de audio con ffmpeg.

Ambas rutas dependen de que ffmpeg esté instalado en el sistema.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlparse

# Formatos de audio soportados y su codec ffmpeg asociado.
AUDIO_CODECS = {
    "mp3": "libmp3lame",
    "wav": "pcm_s16le",
    "m4a": "aac",
    "aac": "aac",
    "flac": "flac",
    "opus": "libopus",
}


class AudioExtractionError(RuntimeError):
    """Error de alto nivel para fallos de extracción."""


def is_url(source: str) -> bool:
    """Devuelve True si `source` parece una URL http(s)."""
    parsed = urlparse(source)
    return parsed.scheme in ("http", "https") and bool(parsed.netloc)


def ensure_ffmpeg() -> str:
    """Verifica que ffmpeg esté disponible y devuelve su ruta."""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise AudioExtractionError(
            "No se encontró 'ffmpeg' en el PATH. Instálalo "
            "(p. ej. 'apt install ffmpeg' o 'brew install ffmpeg')."
        )
    return ffmpeg


def _validate_format(audio_format: str) -> str:
    audio_format = audio_format.lower().lstrip(".")
    if audio_format not in AUDIO_CODECS:
        raise AudioExtractionError(
            f"Formato no soportado: {audio_format!r}. "
            f"Opciones: {', '.join(sorted(AUDIO_CODECS))}."
        )
    return audio_format


def extract_from_file(
    input_path: str | os.PathLike[str],
    output_path: str | os.PathLike[str] | None = None,
    audio_format: str = "mp3",
    bitrate: str = "192k",
    overwrite: bool = False,
) -> Path:
    """Extrae el audio de un archivo de video local con ffmpeg.

    Devuelve la ruta del archivo de audio generado.
    """
    audio_format = _validate_format(audio_format)
    ffmpeg = ensure_ffmpeg()

    src = Path(input_path).expanduser()
    if not src.is_file():
        raise AudioExtractionError(f"No existe el archivo: {src}")

    if output_path is None:
        dst = src.with_suffix(f".{audio_format}")
    else:
        dst = Path(output_path).expanduser()
        if dst.is_dir():
            dst = dst / f"{src.stem}.{audio_format}"

    if dst.exists() and not overwrite:
        raise AudioExtractionError(
            f"El destino ya existe: {dst}. Usa overwrite=True para reemplazarlo."
        )

    dst.parent.mkdir(parents=True, exist_ok=True)

    cmd = [
        ffmpeg,
        "-y" if overwrite else "-n",
        "-i", str(src),
        "-vn",  # sin video
        "-acodec", AUDIO_CODECS[audio_format],
    ]
    # El bitrate no aplica a códecs sin pérdida (wav/flac).
    if audio_format not in ("wav", "flac"):
        cmd += ["-b:a", bitrate]
    cmd.append(str(dst))

    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise AudioExtractionError(
            f"ffmpeg falló (código {proc.returncode}):\n{proc.stderr.strip()}"
        )
    return dst


def extract_from_url(
    url: str,
    output_dir: str | os.PathLike[str] = ".",
    audio_format: str = "mp3",
    bitrate: str = "192k",
    insecure: bool = False,
) -> Path:
    """Descarga un video de una URL y extrae su audio usando yt-dlp.

    Si `insecure` es True, se omite la verificación del certificado TLS
    (útil tras proxies corporativos que interceptan TLS con un certificado
    autofirmado).

    Devuelve la ruta del archivo de audio generado.
    """
    audio_format = _validate_format(audio_format)
    ensure_ffmpeg()

    try:
        import yt_dlp  # import diferido: solo se necesita para URLs
    except ImportError as exc:  # pragma: no cover
        raise AudioExtractionError(
            "Falta 'yt-dlp'. Instálalo con 'pip install yt-dlp'."
        ) from exc

    out_dir = Path(output_dir).expanduser()
    out_dir.mkdir(parents=True, exist_ok=True)

    # yt-dlp escribe '<title>.<ext>'. Capturamos la ruta final con un hook.
    finished_files: list[str] = []

    def _hook(status: dict) -> None:
        if status.get("status") == "finished":
            filename = status.get("filename")
            if filename:
                finished_files.append(filename)

    # Quitamos el bitrate para códecs sin pérdida.
    quality = "0" if audio_format in ("wav", "flac") else bitrate.rstrip("k")

    ydl_opts = {
        "format": "bestaudio/best",
        "outtmpl": str(out_dir / "%(title)s.%(ext)s"),
        "postprocessors": [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": audio_format,
                "preferredquality": quality,
            }
        ],
        "progress_hooks": [_hook],
        "quiet": True,
        "no_warnings": True,
        "nocheckcertificate": insecure,
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)

    # Construimos la ruta de salida final tras el post-procesado.
    base = None
    if finished_files:
        base = Path(finished_files[0])
    elif isinstance(info, dict):
        base = out_dir / f"{info.get('title', 'audio')}"

    if base is not None:
        candidate = base.with_suffix(f".{audio_format}")
        if candidate.exists():
            return candidate

    # Fallback: buscar el archivo más reciente con la extensión esperada.
    matches = sorted(
        out_dir.glob(f"*.{audio_format}"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if matches:
        return matches[0]

    raise AudioExtractionError(
        "La descarga terminó pero no se encontró el archivo de audio resultante."
    )


def extract(
    source: str,
    output: str | os.PathLike[str] | None = None,
    audio_format: str = "mp3",
    bitrate: str = "192k",
    overwrite: bool = False,
    insecure: bool = False,
) -> Path:
    """Punto de entrada unificado: detecta si `source` es URL o archivo local."""
    if is_url(source):
        out_dir = output if output is not None else "."
        return extract_from_url(
            source,
            output_dir=out_dir,
            audio_format=audio_format,
            bitrate=bitrate,
            insecure=insecure,
        )
    return extract_from_file(
        source,
        output_path=output,
        audio_format=audio_format,
        bitrate=bitrate,
        overwrite=overwrite,
    )
