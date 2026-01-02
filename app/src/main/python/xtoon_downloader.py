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
    images = []

    p1 = r'(?:data-src|data-original|data-url)="([^"]+\.(?:jpg|png|webp|jpeg)[^"]*)"'
    matches = re.findall(p1, html)

    for m in matches:
        link = unescape(m)
        if link.startswith("//"):
            link = "https:" + link
        elif link.startswith("/"):
            link = urljoin(url, link)

        if "xtoon" in link or "mangatoon" in link or "toon" in link:
             images.append(link)

    if not images:
        p2 = r'src="([^"]+\.(?:jpg|png|webp|jpeg)[^"]*)"'
        matches = re.findall(p2, html)
        for m in matches:
            link = unescape(m)
            if link.startswith("//"):
                link = "https:" + link
            elif link.startswith("/"):
                link = urljoin(url, link)
            if "logo" not in link and "icon" not in link:
                images.append(link)

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
        # User wants "98화 - 게임은 살인이다" from "98화 - 게임은 살인이다 - Xtoon"
        # We split by " - Xtoon" or just take the first parts.
        raw_title = unescape(m.group(1)).strip()
        # Remove suffix
        clean_title = re.sub(r'\s*-\s*Xtoon.*', '', raw_title, flags=re.IGNORECASE)
        return clean_title
    return "XToon Chapter"
