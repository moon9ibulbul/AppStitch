import requests
from bs4 import BeautifulSoup
import re
from urllib.parse import urljoin, urlparse

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def get_headers(referer=None):
    h = {
        "User-Agent": USER_AGENT,
        "Referer": referer if referer else "https://www.mangago.me/"
    }
    return h

def get_chapter_info(url):
    try:
        r = requests.get(url, headers=get_headers(url), timeout=15)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, 'html.parser')

        title_tag = soup.find('title')
        full_title = title_tag.text.strip() if title_tag else "Unknown MangaGo Series"

        # Clean title
        # "Chapter Title - Series Name | Mangago"
        clean_title = full_title.split('|')[0].strip()

        return {"title": clean_title}
    except Exception as e:
        return {"error": str(e)}

def get_images(url):
    # MangaGo Logic
    # They sometimes have "All pages" mode or single page.
    # We try to extract images from the current page.

    try:
        session = requests.Session()
        session.headers.update(get_headers(url))

        r = session.get(url, timeout=15)
        r.raise_for_status()
        html = r.text
        soup = BeautifulSoup(html, 'html.parser')

        images = []

        # Method 1: Look for img tags with id="pageX" (Long strip mode often has this)
        # The reference repo uses: img[id^='page']

        img_candidates = soup.find_all('img', id=re.compile(r'^page\d+'))
        for img in img_candidates:
            src = img.get('src') or img.get('data-src')
            if src:
                # Resolve relative
                src = urljoin(url, src)
                if src not in images:
                    images.append(src)

        if images:
            return images

        # Method 2: Check for direct image links if method 1 failed
        # Sometimes images are just <img> with specific classes

        # Method 3: Parsing script tags (if they use JS array)
        # var imgsrcs = ['url1', 'url2', ...]

        script_content = ""
        for s in soup.find_all('script'):
            if s.string:
                script_content += s.string

        # Look for array of images
        # common pattern: var imgsrcs = [...]

        matches = re.findall(r'imgsrcs\s*=\s*\[(.*?)\]', script_content, re.DOTALL)
        if matches:
            # Parse the array string
            raw_list = matches[0]
            # Extract quoted strings
            urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
            for u in urls:
                if u and u not in images:
                    if u.startswith('//'): u = 'https:' + u
                    images.append(u)

        return images

    except Exception as e:
        print(f"MangaGo Download Error: {e}")
        return []
