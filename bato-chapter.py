#!/usr/bin/env python3
"""
Bato image downloader (v2/v3/v4 compatible)
- No Playwright
- Robust image URL extraction (k*.mb* / n*.mb*)
- Auto-normalize URL to bato.ing
"""

import argparse
import os
import re
import sys
import time
from pathlib import Path
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# ============================================================
# URL NORMALIZATION
# ============================================================

def normalize_bato_url(url: str) -> str:
    p = urlparse(url)
    path = p.path.rstrip("/")

    # /chapter/4008948  -> /title/_/4008948
    m = re.match(r"^/chapter/(\d+)$", path)
    if m:
        return f"https://bato.ing/title/_/{m.group(1)}"

    # /title/xxx/yyy -> keep path
    if path.startswith("/title/"):
        return f"https://bato.ing{path}"

    # fallback
    return f"https://bato.ing{path}"


# ============================================================
# FETCH HTML
# ============================================================

def fetch_html(url: str) -> str:
    req = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Referer": "https://bato.ing/",
        },
    )
    with urlopen(req, timeout=30) as r:
        charset = r.headers.get_content_charset() or "utf-8"
        return r.read().decode(charset, errors="replace")


# ============================================================
# IMAGE EXTRACTION (CORE FIX)
# ============================================================

IMG_URL_RE = re.compile(
    r"https://[kn]\d+\.mb[^\"'\s<>]+",
    re.IGNORECASE,
)

def extract_images(html: str):
    """
    Strategy:
    1. Direct scan for image CDN URLs (k*.mb* / n*.mb*)
    2. Deduplicate while preserving order
    """

    found = IMG_URL_RE.findall(html)

    if not found:
        raise RuntimeError("Tidak menemukan URL gambar (mb CDN)")

    seen = set()
    images = []
    for url in found:
        if url not in seen:
            seen.add(url)
            images.append(url)

    return images


# ============================================================
# DOWNLOAD
# ============================================================

def sanitize_ext(url: str) -> str:
    path = urlparse(url).path
    _, ext = os.path.splitext(path)
    return ext if ext else ".jpg"


def normalize_image_host(url: str) -> str:
    p = urlparse(url)
    if p.netloc.startswith("k"):
        return urlunparse(p._replace(netloc="n" + p.netloc[1:]))
    return url


def download_image(url: str, out: Path, idx: int):
    url = normalize_image_host(url)
    ext = sanitize_ext(url)
    name = f"img_{idx:04d}{ext}"

    req = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Referer": "https://bato.ing/",
        },
    )
    with urlopen(req, timeout=30) as r:
        out.joinpath(name).write_bytes(r.read())


# ============================================================
# MAIN
# ============================================================

def main():
    ap = argparse.ArgumentParser("Bato downloader (patched)")
    ap.add_argument("url")
    ap.add_argument("output")
    ap.add_argument("--skip-env-setup", action="store_true")
    args = ap.parse_args()

    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    url = normalize_bato_url(args.url)
    print(f"[bato] URL => {url}")

    print("[bato] Fetch page...")
    try:
        html = fetch_html(url)
    except Exception as e:
        print(f"[bato] ERROR fetch HTML: {e}")
        return 1

    print("[bato] Extract images...")
    try:
        images = extract_images(html)
    except Exception as e:
        print(f"[bato] ERROR extract: {e}")
        return 2

    print(f"[bato] Found {len(images)} images")

    start = time.time()
    for i, img in enumerate(images, 1):
        try:
            download_image(img, out_dir, i)
            print(f"[bato] ({i}/{len(images)}) OK")
        except Exception as e:
            print(f"[bato] FAIL {img}: {e}")
            return 3

    print(f"[bato] Done in {time.time() - start:.2f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
