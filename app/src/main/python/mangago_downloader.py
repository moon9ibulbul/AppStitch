import requests
from bs4 import BeautifulSoup
import re
from urllib.parse import urljoin, urlparse

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def get_headers(referer=None, cookie=None):
    h = {
        "User-Agent": USER_AGENT,
        "Referer": referer if referer else "https://www.mangago.me/"
    }
    if cookie:
        h["Cookie"] = cookie
    return h

def get_chapter_info(url, cookie=None):
    try:
        r = requests.get(url, headers=get_headers(url, cookie), timeout=15)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, 'html.parser')

        title_tag = soup.find('title')
        full_title = title_tag.text.strip() if title_tag else "Unknown MangaGo Series"
        clean_title = full_title.split('|')[0].strip()
        return {"title": clean_title}
    except Exception as e:
        return {"error": str(e)}

def get_images(url, cookie=None):
    try:
        session = requests.Session()
        session.headers.update(get_headers(url, cookie))

        r = session.get(url, timeout=15)
        r.raise_for_status()
        html = r.text
        soup = BeautifulSoup(html, 'html.parser')

        images = []
        script_content = ""
        for s in soup.find_all('script'):
            if s.string:
                script_content += s.string

        # 1. Check for 'imgsrcs' JS variable
        matches = re.search(r'var\s+imgsrcs\s*=\s*\[(.*?)\]', script_content, re.DOTALL | re.IGNORECASE)
        if matches:
            raw_list = matches.group(1)
            urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
            for u in urls:
                if u:
                    if u.startswith("//"): u = "https:" + u
                    if u not in images:
                        images.append(u)

        if images:
            return images

        # 2. Check for 'originals' variable (rare fallback)
        matches_orig = re.search(r'var\s+originals\s*=\s*\[(.*?)\]', script_content, re.DOTALL | re.IGNORECASE)
        if matches_orig:
            raw_list = matches_orig.group(1)
            urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
            for u in urls:
                if u:
                    if u.startswith("//"): u = "https:" + u
                    if u not in images:
                        images.append(u)

        if images:
            return images

        # 3. Fallback: Parse <img> tags with id="pageX"
        img_candidates = soup.find_all('img')
        for img in img_candidates:
            if img.get('id') and re.match(r'^page\d+', img.get('id')):
                 src = img.get('src') or img.get('data-src')
                 if src:
                    src = urljoin(url, src)
                    if src not in images:
                        images.append(src)

        return images

    except Exception as e:
        print(f"MangaGo Download Error: {e}")
        return []
