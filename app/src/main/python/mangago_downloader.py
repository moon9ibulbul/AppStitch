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

def get_headers(referer=None, cookie=None, is_image=False):
    # Reference repo uses .zone referer ONLY for images on specific domains
    h = {
        "User-Agent": USER_AGENT
    }

    if is_image:
         h["Referer"] = "https://www.mangago.zone/"

    if cookie:
        h["Cookie"] = cookie
    return h

def get_session(cookie=None):
    # If cookie is present, prefer standard requests first to avoid Cloudscraper interfering with valid sessions
    if cookie:
        return requests.Session()

    if HAS_CLOUDSCRAPER:
        try:
            # browser='chrome' helps mimic a real browser to bypass some CF checks
            scraper = cloudscraper.create_scraper(browser={'browser': 'chrome', 'platform': 'windows', 'mobile': False})
            return scraper
        except Exception as e:
            print(f"Cloudscraper init failed: {e}, falling back to requests")

    return requests.Session()

def get_chapter_info(url, cookie=None):
    try:
        session = get_session(cookie)
        # For HTML page, we don't force the .zone referer
        headers = get_headers(url, cookie, is_image=False)

        r = session.get(url, headers=headers, timeout=20)

        # Fallback to Cloudscraper if requests fails and we haven't tried it yet
        if r.status_code in [403, 503] and cookie and HAS_CLOUDSCRAPER and isinstance(session, requests.Session):
             try:
                 print("Requests failed with 403, retrying with Cloudscraper...")
                 scraper = cloudscraper.create_scraper(browser={'browser': 'chrome', 'platform': 'windows', 'mobile': False})
                 r = scraper.get(url, headers=headers, timeout=20)
             except: pass

        r.raise_for_status()

        soup = BeautifulSoup(r.text, 'html.parser')
        title_tag = soup.find('title')
        full_title = title_tag.text.strip() if title_tag else "Unknown MangaGo Series"
        clean_title = full_title.split('|')[0].strip()

        return {"title": clean_title}
    except Exception as e:
        if "403" in str(e) or "503" in str(e):
             return {"error": "Cloudflare Blocked. Please Open Browser in App to capture cookies (cf_clearance)."}
        return {"error": str(e)}

def extract_images_from_html(html, url, images_list):
    """Helper to extract images from a single page HTML content"""
    soup = BeautifulSoup(html, 'html.parser')

    # Method 1: 'imgsrcs' JS variable (Long strip mode)
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
                        if u not in images_list: images_list.append(u)
                return True # Found full list

            # Variant 2: var originals = [...]
            matches_orig = re.search(r'var\s+originals\s*=\s*\[(.*?)\]', s.string, re.DOTALL | re.IGNORECASE)
            if matches_orig:
                raw_list = matches_orig.group(1)
                urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
                for u in urls:
                    if u:
                        if u.startswith("//"): u = "https:" + u
                        if u not in images_list: images_list.append(u)
                return True

    # Method 2: DOM Parsing (Single Page or Fallback)
    img_candidates = soup.find_all('img')
    found_on_page = False
    for img in img_candidates:
        eid = img.get('id', '')
        ecls = img.get('class', [])

        is_page_img = False
        # Matches id="page1", "pageX"
        if eid and re.match(r'^page\d+', eid): is_page_img = True

        if not is_page_img:
            # Matches class="page1"
            for c in ecls:
                if re.match(r'^page\d+', c):
                    is_page_img = True
                    break

        if is_page_img:
            src = img.get('src') or img.get('data-src')
            if src:
                src = urljoin(url, src)
                if "loading" not in src.lower() and src not in images_list:
                    images_list.append(src)
                    found_on_page = True

    return found_on_page

def get_images(url, cookie=None):
    try:
        session = get_session(cookie)
        # HTML fetch: No Image Referer
        headers = get_headers(url, cookie, is_image=False)

        r = session.get(url, headers=headers, timeout=20)

        # Fallback retry logic
        if r.status_code in [403, 503] and cookie and HAS_CLOUDSCRAPER and isinstance(session, requests.Session):
             try:
                 print("Requests failed with 403, retrying with Cloudscraper...")
                 scraper = cloudscraper.create_scraper(browser={'browser': 'chrome', 'platform': 'windows', 'mobile': False})
                 r = scraper.get(url, headers=headers, timeout=20)
             except: pass

        if r.status_code in [403, 503]:
            # Check for Cloudflare specifics
            if "Just a moment" in r.text or "Cloudflare" in r.text or "challenge" in r.text.lower():
                raise Exception("Cloudflare blocked. Please Open Browser in App to capture cookies.")
            r.raise_for_status()

        images = []
        # Try extracting from initial page
        full_list_found = extract_images_from_html(r.text, url, images)

        # If we found a full list via JS, return immediately
        if full_list_found and len(images) > 1:
            return images

        # Check for pagination if we only found 1 image or none, and no JS list
        soup = BeautifulSoup(r.text, 'html.parser')

        # Logic 1: .multi_pg_tip.left (Generic Mangago pagination)
        # Text example: "Total pages: ( 1 / 15 )"
        page_tip = soup.select_one(".multi_pg_tip.left")
        total_pages = 0

        if page_tip:
            txt = page_tip.get_text(strip=True)
            # Extract denominator
            parts = txt.split('/')
            if len(parts) > 1:
                try:
                    total_pages = int(re.sub(r'[^\d]', '', parts[-1]))
                except: pass

        # Logic 2: Dropdown check if Tip failed
        if total_pages == 0:
            select = soup.find('select', id='page-dropdown')
            if select:
                options = select.find_all('option')
                if options:
                    total_pages = len(options)

        # Iterate pages if we have > 1 page and didn't find a bulk list
        if total_pages > 1:
            # Determine URL pattern
            base_url = url
            if "/pg-" in url:
                base_url = re.sub(r'pg-\d+/?', '', url)
                if not base_url.endswith('/'): base_url += '/'
                for i in range(2, total_pages + 1):
                    next_url = f"{base_url}pg-{i}/"
                    try:
                        # For subsequent pages, referer is the previous page
                        headers["Referer"] = url
                        resp = session.get(next_url, headers=headers, timeout=15)
                        if resp.status_code == 200:
                            extract_images_from_html(resp.text, next_url, images)
                    except Exception as e:
                        print(f"Failed to fetch page {i}: {e}")
            else:
                url_stripped = url.rstrip('/')
                if url_stripped[-1].isdigit():
                     base_url = re.sub(r'/\d+$', '', url_stripped) + '/'
                else:
                     base_url = url_stripped + '/'

                for i in range(2, total_pages + 1):
                    next_url = f"{base_url}{i}/"
                    try:
                        headers["Referer"] = url
                        resp = session.get(next_url, headers=headers, timeout=15)
                        if resp.status_code == 200:
                             extract_images_from_html(resp.text, next_url, images)
                    except: pass

        if not images:
            raise Exception("No images found. Site structure might have changed or Cloudflare blocked scripts.")

        return images

    except Exception as e:
        print(f"MangaGo Download Error: {e}")
        # Ensure the UI gets the clear message
        raise e
