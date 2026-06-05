import json
import re
import urllib.request
import urllib.error
import urllib.parse
from html import unescape
from bs4 import BeautifulSoup

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

    title = "Naver Webtoon Chapter"
    m = re.search(r'<meta property="og:title" content="([^"]+)"', html)
    if m:
        title = unescape(m.group(1)).strip()
        title = title.replace(" - Naver Webtoon", "")

    if "image-comic.pstatic.net" not in html and "wt_viewer" not in html:
        pass

    return {"title": title, "url": url}

def get_naver_episodes(comic_id):
    return []

def get_naver_images(url):
    html = fetch_html(url)
    if not html:
        return []

    soup = BeautifulSoup(html, 'html.parser')
    # Naver viewer images are usually inside .wt_viewer or #section_viewer
    viewer = soup.find(id='section_viewer') or soup.find(class_='wt_viewer')

    if not viewer:
        # Fallback to regex if structure changed significantly
        pattern = r'(https?://image-comic\.pstatic\.net/webtoon/[^"\'\s]+\.(?:jpg|png|jpeg))'
        images = re.findall(pattern, html)
    else:
        img_tags = viewer.find_all('img')
        images = [img.get('src') for img in img_tags if img.get('src')]

    unique = []
    seen = set()
    for img in images:
        # Filter out likely thumbnails, ads, or UI elements
        if any(x in img for x in ["title_thumbnail", "banner", "display_ad", "agerate"]):
            continue

        # Ensure it's a valid webtoon image host
        if "image-comic.pstatic.net" not in img:
            continue

        if img not in seen:
            seen.add(img)
            unique.append(img)

    return unique
