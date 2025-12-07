"""Downloader untuk sumber Bato.to berbasis konstanta `imgHttps`.

Mengunduh daftar gambar dari halaman chapter dan menyimpannya ke folder bernama
sesuai judul halaman (disanitasi untuk nama folder yang aman).
"""

import json
import os
import re
import shutil
import time
from html import unescape
from pathlib import Path
from typing import Optional
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/118.0.0.0 Safari/537.36"
)


def fetch_html(url: str) -> str:
    req = Request(url, headers={"User-Agent": USER_AGENT, "Referer": url})
    with urlopen(req) as resp:
        charset = resp.headers.get_content_charset() or "utf-8"
        data = resp.read()
    return data.decode(charset, errors="replace")


def extract_title(html: str) -> str:
    match = re.search(r"<title>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    if not match:
        return "bato_chapter"
    title = unescape(match.group(1)).strip()
    title = re.sub(r"\s+", " ", title)
    return title or "bato_chapter"


def sanitize_folder_name(title: str) -> str:
    sanitized = re.sub(r"[\\/:*?\"<>|]", "_", title).strip(" .")
    return sanitized or "bato_chapter"


def extract_images(html: str):
    pattern = re.compile(r"const\s+imgHttps\s*=\s*(\[[^;]*\])", re.IGNORECASE | re.DOTALL)
    match = pattern.search(html)
    if match:
        array_text = match.group(1)
        try:
            data = json.loads(array_text)
        except json.JSONDecodeError:
            normalized = array_text.replace("'", '"')
            data = json.loads(normalized)
        if not isinstance(data, list):
            raise ValueError("imgHttps bukan list")
        return [str(item) for item in data if isinstance(item, str) and item.strip()]

    astro_images = extract_images_from_astro(html)
    if astro_images:
        return astro_images
    raise ValueError("Tidak menemukan konstanta imgHttps maupun data astro-island")


def extract_images_from_astro(html: str):
    pattern = re.compile(r"<astro-island[^>]+props=\"([^\"]+)\"", re.IGNORECASE)
    urls = []
    for match in pattern.finditer(html):
        props_raw = match.group(1)
        props_text = unescape(props_raw)
        try:
            props = json.loads(props_text)
        except json.JSONDecodeError:
            continue
        image_files = props.get("imageFiles")
        if not image_files or len(image_files) < 2:
            continue
        encoded = image_files[1]
        if not isinstance(encoded, str):
            continue
        try:
            decoded = json.loads(encoded)
        except json.JSONDecodeError:
            continue
        for item in decoded:
            if isinstance(item, list) and len(item) >= 2 and isinstance(item[1], str) and item[1].strip():
                urls.append(item[1])
    return urls


def sanitize_ext(url: str) -> str:
    path = urlparse(url).path
    ext = os.path.splitext(path)[1]
    if not ext:
        return ".jpg"
    return ext if ext.startswith('.') else f".{ext}"


def _fallback_n10_url(url: str) -> Optional[str]:
    """Ganti prefix domain kXX.*.* menjadi n10.*.* untuk URL Bato."""

    parsed = urlparse(url)
    host = parsed.hostname
    if not host:
        return None

    match = re.match(r"k\d{2}(\..+)$", host)
    if not match:
        return None

    new_host = f"n10{match.group(1)}"
    netloc = new_host
    if parsed.port:
        netloc = f"{new_host}:{parsed.port}"
    return parsed._replace(netloc=netloc).geturl()


def download_image(url: str, dest: Path, idx: int, referer: str):
    ext = sanitize_ext(url)
    filename = f"img_{idx:04d}{ext}"
    target = dest / filename
    current_url = url

    for attempt in range(2):
        req = Request(current_url, headers={"User-Agent": USER_AGENT, "Referer": referer})
        try:
            with urlopen(req) as resp:
                chunk = resp.read()
            target.write_bytes(chunk)
            return target
        except HTTPError as err:
            if err.code != 503:
                raise
            fallback_url = _fallback_n10_url(current_url)
            if not fallback_url or fallback_url == current_url:
                raise
            current_url = fallback_url

    raise RuntimeError("Gagal mengunduh gambar setelah mencoba fallback domain n10")


def normalize_bato_url(url: str) -> str:
    """Konversi domain alternatif Bato ke domain utama bato.to."""

    parsed = urlparse(url)
    domain = parsed.netloc.split(":", 1)[0].lower()
    if domain.startswith("www."):
        domain = domain[4:]

    if domain.startswith("bato.si") or domain.startswith("bato.ing"):
        match = re.search(r"/(\d+)(?:-[^/]*)?/?$", parsed.path)
        if not match:
            raise ValueError("Tidak dapat menemukan chapter id dari URL yang diberikan")
        chapter_id = match.group(1)
        return f"https://bato.to/chapter/{chapter_id}"

    return url


def _write_progress(progress_path: Optional[str], processed: int, total: int):
    if not progress_path:
        return
    try:
        with open(progress_path, "w") as f:
            json.dump({"processed": processed, "total": total}, f)
    except Exception:
        pass


def download_bato(url: str, output_dir: str, progress_path: Optional[str] = None,
                  start: int = 0, extra_total: int = 0) -> str:
    """Unduh gambar-gambar dari URL Bato dan simpan di folder judul halaman."""

    normalized_url = normalize_bato_url(url)
    html = fetch_html(normalized_url)
    images = extract_images(html)
    if not images:
        raise RuntimeError("Tidak ada gambar ditemukan pada halaman Bato")

    title = sanitize_folder_name(extract_title(html))
    base_dir = Path(output_dir)
    base_dir.mkdir(parents=True, exist_ok=True)
    target_dir = base_dir / title
    if target_dir.exists():
        shutil.rmtree(target_dir)
    target_dir.mkdir(parents=True, exist_ok=True)

    total_steps = start + len(images) + max(0, extra_total)
    _write_progress(progress_path, start, total_steps)

    start_time = time.time()
    for idx, img_url in enumerate(images, start=1):
        download_image(img_url, target_dir, idx, normalized_url)
        _write_progress(progress_path, start + idx, total_steps)
    duration = time.time() - start_time
    print(f"[bato] Mengunduh {len(images)} gambar selesai dalam {duration:.2f}s")
    return json.dumps({"path": str(target_dir), "count": len(images)})


__all__ = [
    "download_bato",
]
