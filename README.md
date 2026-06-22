# audio-extractor

Herramienta CLI en Python para **extraer audio** desde:

- **URLs** de video (TikTok, YouTube, Instagram, etc.) — descarga con [`yt-dlp`](https://github.com/yt-dlp/yt-dlp).
- **Archivos de video locales** (`.mp4`, `.mov`, `.mkv`, …) — extracción directa con `ffmpeg`.

Detecta automáticamente si el origen es una URL o una ruta local.

## Requisitos

- Python 3.9+
- [`ffmpeg`](https://ffmpeg.org/) instalado y disponible en el `PATH`
  - Debian/Ubuntu: `sudo apt install ffmpeg`
  - macOS: `brew install ffmpeg`

## Instalación

```bash
pip install -e .
# o solo las dependencias:
pip install -r requirements.txt
```

## Uso

```bash
# Desde una URL (TikTok / YouTube / etc.) → MP3 en la carpeta actual
audio-extractor "https://www.tiktok.com/@usuario/video/123456789"

# Desde un archivo local → MP3 junto al original
audio-extractor video.mp4

# Elegir formato, bitrate y destino
audio-extractor video.mov -f wav -o ./salida/
audio-extractor "https://youtu.be/xxxx" -f mp3 -b 320k -o ~/Música

# Sobrescribir si ya existe (archivos locales)
audio-extractor video.mp4 --overwrite
```

También puedes ejecutarlo como módulo sin instalar:

```bash
python -m audio_extractor video.mp4
```

### Opciones

| Opción | Descripción | Por defecto |
|--------|-------------|-------------|
| `source` | URL del video o ruta a un archivo local | — |
| `-o, --output` | Archivo (local) o carpeta (URL) de destino | junto al original / carpeta actual |
| `-f, --format` | `mp3`, `wav`, `m4a`, `aac`, `flac`, `opus` | `mp3` |
| `-b, --bitrate` | Bitrate, p. ej. `128k`, `192k`, `320k` | `192k` |
| `--overwrite` | Sobrescribe la salida (solo archivos locales) | desactivado |

## Uso como librería

```python
from audio_extractor import extract

# URL o archivo local; devuelve la ruta del audio generado
ruta = extract("video.mp4", audio_format="mp3", bitrate="192k")
print(ruta)
```

## Notas

- Para extraer de un video de TikTok, copia el enlace de "compartir" y pásalo entre comillas.
- Los formatos `wav` y `flac` son sin pérdida; el bitrate se ignora para ellos.
