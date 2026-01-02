import re
import urllib.request
import urllib.error
from html import unescape
from urllib.parse import urljoin

def fetch_html(url, headers=None):
    if headers is None:
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer": url
        }
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read().decode('utf-8', errors='replace')
    except Exception as e:
        print(f"Error fetching {url}: {e}")
        return ""

def get_xtoon_images(url):
    html = fetch_html(url)

    # XToon usually lazy loads or puts images in data-src
    # Based on `xtoon.py`:
    # <div id="pic_..."> <img ... data-src="...">

    # Simple regex approach matching common patterns

    images = []

    # Pattern 1: lazy loaded
    p1 = r'(?:data-src|data-original|data-url)="([^"]+\.(?:jpg|png|webp|jpeg)[^"]*)"'
    matches = re.findall(p1, html)

    # Pattern 2: src direct
    # But be careful not to grab UI elements.
    # Usually XToon images are in a specific container.

    # Let's trust the regex from the original `xtoon.py` but simplified
    # Original used bs4, we want to avoid extra deps if possible, but strict regex is okay.

    # If regex fails, we return empty.

    for m in matches:
        # unescape
        link = unescape(m)
        if link.startswith("//"):
            link = "https:" + link
        elif link.startswith("/"):
            link = urljoin(url, link)

        if "xtoon" in link or "mangatoon" in link or "toon" in link: # Basic filtering
             images.append(link)

    # If empty, try src
    if not images:
        p2 = r'src="([^"]+\.(?:jpg|png|webp|jpeg)[^"]*)"'
        matches = re.findall(p2, html)
        for m in matches:
            link = unescape(m)
            if link.startswith("//"):
                link = "https:" + link
            elif link.startswith("/"):
                link = urljoin(url, link)
            # Filter UI images
            if "logo" not in link and "icon" not in link:
                images.append(link)

    # Unique
    seen = set()
    unique = []
    for i in images:
        if i not in seen:
            seen.add(i)
            unique.append(i)

    return unique

def get_xtoon_title(url):
    html = fetch_html(url)
    m = re.search(r'<title>(.*?)</title>', html)
    if m:
        return unescape(m.group(1)).split("-")[0].strip()
    return "XToon Chapter"
