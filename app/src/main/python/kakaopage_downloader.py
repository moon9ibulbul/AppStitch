import requests
import re
import json
from urllib.parse import urlparse

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def extract_ids_from_url(url):
    """
    Extracts seriesId and productId from URL.
    Example: https://page.kakao.com/content/64399835/viewer/64400919
    seriesId = 64399835
    productId = 64400919
    """
    path = urlparse(url).path
    m = re.search(r'/content/(\d+)/viewer/(\d+)', path)
    if m:
        return m.group(1), m.group(2)
    return None, None

def _fetch_data(url, cookie=None):
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": url,
        "Content-Type": "application/json",
        "Origin": "https://page.kakao.com",
        "Accept": "application/json, text/plain, */*",
        "X-Requested-With": "XMLHttpRequest"
    }
    if cookie:
        headers["Cookie"] = cookie

    series_id, product_id = extract_ids_from_url(url)
    if not product_id:
        raise ValueError(f"KakaoPage: Could not parse product ID from URL: {url}")

    # Use the REST API instead of GraphQL
    api_url = "https://bff-page.kakao.com/api/gateway/api/v1/viewer/data"
    payload = {
        "productId": product_id,
        "seriesId": series_id,
        "deviceType": "WEB",
        "isPreView": False
    }

    response = requests.post(api_url, json=payload, headers=headers, timeout=30)
    response.raise_for_status()
    return response.json()

def get_chapter_info(url, cookie=None):
    title = "KakaoPage Item"
    try:
        # Fallback to HTML scraping for title as it's more reliable
        headers = {"User-Agent": USER_AGENT, "Referer": "https://page.kakao.com/"}
        if cookie: headers["Cookie"] = cookie

        resp = requests.get(url, headers=headers, timeout=15)
        if resp.ok:
            m = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.I)
            if m:
                title = m.group(1).replace(" - 카카오페이지", "").strip()
                # Clean up title: [독점] etc
                title = re.sub(r'\[.*?\]', '', title).strip()
                if title: return {"title": title}

        # If HTML fails, try API
        data = _fetch_data(url, cookie)
        title = data.get("item", {}).get("title") or data.get("series", {}).get("title") or "KakaoPage Item"
    except Exception as e:
        print(f"KakaoPage Info Error: {e}")

    return {"title": title}

def get_images(url, cookie=None):
    try:
        data = _fetch_data(url, cookie)

        viewer_data = data.get("viewerData", {})
        if viewer_data.get("type") == "ImageViewerData":
            files = viewer_data.get("imageDownloadData", {}).get("files", [])
            files.sort(key=lambda x: x.get('no', 0))
            urls = [f.get("secureUrl") for f in files if f.get("secureUrl")]
            return urls

        print("KakaoPage: Viewer data type is not ImageViewerData or no data found.")
        return []

    except Exception as e:
        print(f"KakaoPage API Error: {e}")
        return []
