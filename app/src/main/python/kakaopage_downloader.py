import requests
import re
import json
from urllib.parse import unquote

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def get_images(url, cookie=None):
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://page.kakao.com/",
    }
    if cookie:
        headers["Cookie"] = cookie

    try:
        response = requests.get(url, headers=headers, timeout=30)
        response.raise_for_status()
        html = response.text

        # Look for __NEXT_DATA__
        m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.+?)</script>', html)
        if not m:
            # Fallback: simple regex scan
            print("KakaoPage: __NEXT_DATA__ not found. Trying regex scan.")
            return extract_images_regex(html)

        json_str = m.group(1)
        data = json.loads(json_str)

        images = extract_files_recursive(data)
        if not images:
             print("KakaoPage: No images found in __NEXT_DATA__. Trying regex scan.")
             return extract_images_regex(html)

        # Sort by 'no' if available
        images.sort(key=lambda x: x.get('no', 0))
        urls = [img['secureUrl'] for img in images if 'secureUrl' in img]

        return urls

    except Exception as e:
        print(f"KakaoPage Error: {e}")
        return []

def extract_files_recursive(data):
    if isinstance(data, dict):
        if data.get('type') == 'ImageViewerData':
            files = data.get('imageDownloadData', {}).get('files', [])
            if files:
                return files

        for k, v in data.items():
            res = extract_files_recursive(v)
            if res: return res

    elif isinstance(data, list):
        for item in data:
            res = extract_files_recursive(item)
            if res: return res

    return None

def extract_images_regex(html):
    # Regex fallback
    # Matches secureUrl pattern if present in js variables
    pattern = r'"secureUrl":"(https?://[^"]+)"'
    found = re.findall(pattern, html)
    # unescape json unicode if needed, though requests.text handles utf8.
    # But json strings might have escaped chars.
    unique = []
    seen = set()
    for f in found:
        f = f.replace("\\u0026", "&")
        if f not in seen:
            seen.add(f)
            unique.append(f)
    return unique
