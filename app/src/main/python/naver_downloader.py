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
    # <meta property="og:title" content="Title">
    m = re.search(r'<meta property="og:title" content="([^"]+)"', html)
    if m:
        return m.group(1).replace(" - Naver Webtoon", "").strip()
    return f"Naver Webtoon {comic_id}"

def get_naver_episodes(comic_id):
    """
    Fetches all episodes for a comic ID.
    Naver usually paginates, but we can try to fetch the list or iterate.
    For simplicity and reliability, we can scrape the list page.
    However, the prompt asks for "Comic ID" as input, which implies we should likely fetch chapters.

    Actually, to match Bato logic, if the input is a Series, we return a list of chapters.
    If it's a specific chapter, we return images.

    But Naver ID `123456` is a Series ID.
    So we should return a list of chapters (episodes).
    """
    base_url = f"https://comic.naver.com/webtoon/list?titleId={comic_id}"
    html = fetch_html(base_url)
    title = get_naver_title(comic_id)

    chapters = []

    # Simple pagination loop or just grabbing first page for now?
    # Naver lists can be long.
    # We'll scrape the current page and maybe check for 'next'.
    # For this task, let's just grab visible episodes on the main list page to start.
    # Usually users want the latest or all.
    # Naver list table: <td class="title"> <a href="/webtoon/detail?titleId=...&no=...">...</a>

    # Note: Modern Naver might be React/API based.
    # Checking `naver.py` (the old one) suggests it iterates range `start` to `end`.
    # Since we don't have start/end inputs in the generic UI, we might need to be smart.
    # But for a "Queue" system, adding a Series usually implies "Add all chapters to queue" or "Select chapters".
    # The current Bato implementation adds ALL found chapters in the series page.

    # Regex for links: /webtoon/detail\?titleId=758037&no=123
    seen_nos = set()

    # We might need to iterate pages. ?page=1, ?page=2
    page = 1
    while True:
        p_url = f"{base_url}&page={page}"
        p_html = fetch_html(p_url)

        # Check if any episodes found
        pattern = r'href="(/webtoon/detail\?titleId=' + str(comic_id) + r'&no=(\d+)[^"]*)"[^>]*>(.*?)</a>'
        matches = re.findall(pattern, p_html)

        if not matches:
            break

        found_new = False
        for href, no, ch_title in matches:
            if no in seen_nos:
                continue
            seen_nos.add(no)
            found_new = True

            # Clean title
            ch_title = unescape(ch_title).strip()
            # Naver titles often are just dates or "Ep. 1" inside the link text?
            # Actually link text is usually the title.

            full_url = f"https://comic.naver.com{href}"
            chapters.append({
                "url": full_url,
                "title": f"{title} - {ch_title} (Ep {no})"
            })

        if not found_new:
            break

        page += 1
        if page > 10: # Limit for safety if endless
            break

    # Sort by number? Usually they come descending.
    return chapters

def get_naver_images(url):
    # url: https://comic.naver.com/webtoon/detail?titleId=...&no=...
    html = fetch_html(url)

    # Images are usually in .wt_viewer or similar div
    # <div class="wt_viewer" style="..."> <img src="..."> </div>
    # Regex for images

    # Need to be careful about matching ads or thumbnails.
    # Naver images usually host on `image-comic.pstatic.net`

    pattern = r'(https?://image-comic\.pstatic\.net/webtoon/[^"]+\.(?:jpg|png|jpeg))'
    images = re.findall(pattern, html)

    # Remove duplicates and thumbnails
    # Thumbnails often have `search` or `list` in path?
    # The viewer images are usually straight forward.

    unique = []
    seen = set()
    for img in images:
        if img not in seen:
            seen.add(img)
            unique.append(img)

    return unique
