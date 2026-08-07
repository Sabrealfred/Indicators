"""Pruebas para audio_extractor.core (sin red ni descargas reales)."""

import subprocess

import pytest

from audio_extractor import core


def test_is_url():
    assert core.is_url("https://www.tiktok.com/@u/video/1")
    assert core.is_url("http://example.com/v.mp4")
    assert not core.is_url("video.mp4")
    assert not core.is_url("/ruta/local/video.mov")
    assert not core.is_url("")


def test_validate_format_ok():
    assert core._validate_format("MP3") == "mp3"
    assert core._validate_format(".wav") == "wav"


def test_validate_format_invalid():
    with pytest.raises(core.AudioExtractionError):
        core._validate_format("xyz")


def test_extract_from_file_missing(tmp_path):
    with pytest.raises(core.AudioExtractionError):
        core.extract_from_file(tmp_path / "no_existe.mp4")


def _has_ffmpeg() -> bool:
    import shutil

    return shutil.which("ffmpeg") is not None


@pytest.mark.skipif(not _has_ffmpeg(), reason="ffmpeg no disponible")
def test_extract_from_file_real(tmp_path):
    """Genera un video sintético con ffmpeg y extrae su audio."""
    video = tmp_path / "tono.mp4"
    subprocess.run(
        [
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-f", "lavfi", "-i", "color=c=black:s=64x64:d=1",
            "-shortest", str(video),
        ],
        check=True,
        capture_output=True,
    )

    out = core.extract_from_file(video, audio_format="mp3", overwrite=True)
    assert out.exists()
    assert out.suffix == ".mp3"
    assert out.stat().st_size > 0
