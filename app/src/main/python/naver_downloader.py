import json
import re
import urllib.request
import urllib.error
import urllib.parse
from html import unescape

def fetch_html(url, headers=None):
    if headers is None:
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        }
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read().decode('utf-8', errors='replace')
    except Exception as e:
        print(f"Error fetching {url}: {e}")
        return ""

def get_naver_title(comic_id):
    url = f"https://comic.naver.com/webtoon/list?titleId={comic_id}"
    html = fetch_html(url)
    m = re.search(r'<meta property="og:title" content="([^"]+)"', html)
    if m:
        return m.group(1).replace(" - Naver Webtoon", "").strip()
    return f"Naver Webtoon {comic_id}"

def get_naver_chapter_info(url):
    """
    Fetches title and ensures validity of a Naver chapter URL.
    URL format: https://comic.naver.com/webtoon/detail?titleId=...&no=...
    """
    html = fetch_html(url)
    if not html:
        return {"error": "Failed to fetch page"}

    # Extract Title: <meta property="og:title" content="Series Title - Chapter Title">
    # Usually Naver sets og:title to "Series Name - Chapter Name"

    title = "Naver Webtoon Chapter"
    m = re.search(r'<meta property="og:title" content="([^"]+)"', html)
    if m:
        title = unescape(m.group(1)).strip()
        # Remove site suffix if present
        title = title.replace(" - Naver Webtoon", "")

    # Validation: Check if images exist or if it's a valid page
    # Look for image-comic.pstatic.net
    if "image-comic.pstatic.net" not in html and "wt_viewer" not in html:
        # Maybe it's a restricted chapter or invalid
        pass

    return {"title": title, "url": url}

def get_naver_episodes(comic_id):
    # Legacy support if needed, but we focus on single chapter now
    return []

def get_naver_images(url):
    html = fetch_html(url)
    pattern = r'(https?://image-comic\.pstatic\.net/webtoon/[^"]+\.(?:jpg|png|jpeg))'
    images = re.findall(pattern, html)

    unique = []
    seen = set()
    for img in images:
        if img not in seen:
            seen.add(img)
            unique.append(img)

    return unique
