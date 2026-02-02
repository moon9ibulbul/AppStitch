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
    # 1. Try extracting 'imgsrcs' (JS variable) - used for All Pages mode
    # 2. Try parsing long-strip images if pre-loaded
    # 3. Fallback: Detect pagination and fetch page-by-page

    try:
        session = requests.Session()
        headers = get_headers(url)
        session.headers.update(headers)

        r = session.get(url, timeout=20)
        r.raise_for_status()
        html = r.text
        soup = BeautifulSoup(html, 'html.parser')

        images = []

        # --- Strategy 1: JS Variable 'imgsrcs' ---
        script_content = ""
        for s in soup.find_all('script'):
            if s.string:
                script_content += s.string

        matches = re.findall(r'imgsrcs\s*=\s*\[(.*?)\]', script_content, re.DOTALL)
        if matches:
            raw_list = matches[0]
            urls = re.findall(r'[\'"](.*?)[\'"]', raw_list)
            for u in urls:
                if u and u not in images:
                    if u.startswith('//'): u = 'https:' + u
                    images.append(u)
            if images:
                return images

        # --- Strategy 2: Pre-loaded Long Strip ---
        # Look for img tags with id="page1", "page2", etc.
        img_candidates = soup.find_all('img', id=re.compile(r'^page\d+'))
        for img in img_candidates:
            src = img.get('src') or img.get('data-src')
            if src:
                src = urljoin(url, src)
                if src not in images:
                    images.append(src)

        if len(images) > 1: # If we found multiple images, we are likely good
            return images

        # --- Strategy 3: Pagination Fallback ---
        # If we only found 0 or 1 image, checking for pagination
        # Look for total pages info, usually in ".multi_pg_tip.left" => "... of 35"

        # Reset images if we only found the first one and going for pagination
        # (Though usually page 1 image is fine to keep, but let's be clean)

        page_info_div = soup.select_one('.multi_pg_tip.left') # mangago.me specific
        if not page_info_div:
            # Maybe it is just 1 page?
            if images: return images
            return []

        # Extract "of X"
        text = page_info_div.get_text(strip=True)
        # Usually "1 of 35" or similar
        parts = text.split('of')
        if len(parts) < 2:
            if images: return images
            return []

        try:
            total_pages = int(re.sub(r'\D', '', parts[-1]))
        except:
            if images: return images
            return []

        if total_pages <= 1:
             if images: return images
             return []

        # Pagination Loop
        # Page 1 is already loaded? Maybe not the high res one?
        # Usually URL structure is: base_url/1/, base_url/2/ OR base_url (for 1) then base_url/2/

        # We need to construct page URLs.
        # Check if current url ends with /1/ or just /

        base_url = url.rstrip('/')
        if base_url.endswith('/1'):
            base_url = base_url[:-2] # remove /1

        images = [] # Clear and refetch all to be safe and ordered

        for i in range(1, total_pages + 1):
            page_url = f"{base_url}/{i}/"
            # Page 1 might be the base url itself
            if i == 1:
                # Some sites redirect /1/ to base, some don't. Try /1/ first as it's cleaner if supported
                pass

            # Fetch page
            try:
                # Ensure we use referer of the previous page or the chapter root
                session.headers.update({"Referer": base_url})

                pr = session.get(page_url, timeout=10)
                if pr.status_code != 200 and i == 1:
                    # Fallback for page 1 if /1/ fails
                    pr = session.get(base_url, timeout=10)

                if pr.status_code == 200:
                    p_soup = BeautifulSoup(pr.text, 'html.parser')
                    # Find the main image. Usually img id="page{i}" or just the main content img
                    # MangaGo usually has <img id="pageX" src="..." />

                    # Try specific ID first
                    img_tag = p_soup.find('img', id=f"page{i}")
                    if not img_tag:
                        # Try class
                        img_tag = p_soup.find('img', class_=f"page{i}")

                    if not img_tag and i==1:
                         # Fallback for page 1 generic find
                         img_tag = p_soup.find('img', id="page1")

                    if img_tag:
                        src = img_tag.get('src') or img_tag.get('data-src')
                        if src:
                            src = urljoin(page_url, src)
                            images.append(src)
                    else:
                        print(f"Warning: No image found on page {i}")
            except Exception as ex:
                print(f"Error fetching page {i}: {ex}")

        return images

    except Exception as e:
        print(f"MangaGo Download Error: {e}")
        return []
