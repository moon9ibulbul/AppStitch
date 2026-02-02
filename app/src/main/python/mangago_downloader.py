import requests
import re
from bs4 import BeautifulSoup
from urllib.parse import urljoin

try:
    import cloudscraper
    HAS_CLOUDSCRAPER = True
except ImportError:
    HAS_CLOUDSCRAPER = False

# Headers from reference repo + generic Desktop UA
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def get_headers(referer=None, cookie=None):
    # Reference repo uses .zone referer for these domains
    if referer and ("mangago" in referer or "youhim" in referer):
        ref = "https://www.mangago.zone/"
    else:
        ref = "https://www.mangago.zone/" # Default to .zone as per reference

    h = {
        "User-Agent": USER_AGENT,
        "Referer": ref
    }
    if cookie:
        h["Cookie"] = cookie
    return h

def get_session(cookie=None):
    if HAS_CLOUDSCRAPER:
        try:
            # browser='chrome' helps mimic a real browser to bypass some CF checks
            scraper = cloudscraper.create_scraper(browser={'browser': 'chrome', 'platform': 'windows', 'mobile': False})
            if cookie:
                scraper.headers.update({"Cookie": cookie})
            return scraper
        except Exception as e:
            print(f"Cloudscraper init failed: {e}, falling back to requests")

    sess = requests.Session()
    if cookie:
        sess.headers.update({"Cookie": cookie})
    return sess

def get_chapter_info(url, cookie=None):
    try:
        session = get_session(cookie)
        session.headers.update(get_headers(url, cookie))

        r = session.get(url, timeout=20)
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
        session = get_session(cookie)
        session.headers.update(get_headers(url, cookie))

        r = session.get(url, timeout=20)

        # Check for soft-block or CF challenge
        if r.status_code in [403, 503]:
            # Try to return specific error
            if "Just a moment" in r.text or "Cloudflare" in r.text:
                raise Exception("Cloudflare blocked. Please provide a valid Cookie (cf_clearance) in Settings.")
            r.raise_for_status()

        html = r.text
        soup = BeautifulSoup(html, 'html.parser')
        images = []

        # Method 1: 'imgsrcs' JS variable (Most common for Mangago)
        # Look for script tags
        scripts = soup.find_all('script')
        for s in scripts:
            if s.string:
                # Variant 1: var imgsrcs = [...]
                matches = re.search(r'var\s+imgsrcs\s*=\s*\[(.*?)\]', s.string, re.DOTALL | re.IGNORECASE)
                if matches:
                    raw_list = matches.group(1)
                    urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
                    for u in urls:
                        if u:
                            if u.startswith("//"): u = "https:" + u
                            if u not in images: images.append(u)
                    if images: return images

                # Variant 2: var originals = [...]
                matches_orig = re.search(r'var\s+originals\s*=\s*\[(.*?)\]', s.string, re.DOTALL | re.IGNORECASE)
                if matches_orig:
                    raw_list = matches_orig.group(1)
                    urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
                    for u in urls:
                        if u:
                            if u.startswith("//"): u = "https:" + u
                            if u not in images: images.append(u)
                    if images: return images

        # Method 2: Fallback to DOM parsing (Lazy loaded images)
        # Look for img tags with id="page1", "page2" etc.
        img_candidates = soup.find_all('img')
        for img in img_candidates:
            # Check for id="pageX" or class="pageX"
            eid = img.get('id', '')
            ecls = img.get('class', [])

            is_page_img = False
            if eid and re.match(r'^page\d+', eid): is_page_img = True
            if not is_page_img:
                for c in ecls:
                    if re.match(r'^page\d+', c):
                        is_page_img = True
                        break

            if is_page_img:
                src = img.get('src') or img.get('data-src')
                if src:
                    src = urljoin(url, src)
                    # Filter out spacer/loading images if possible
                    if "loading" not in src.lower() and src not in images:
                        images.append(src)

        if not images:
            raise Exception("No images found. Site structure might have changed or Cloudflare blocked scripts.")

        return images

    except Exception as e:
        print(f"MangaGo Download Error: {e}")
        # Re-raise to let the UI show the error
        raise e
