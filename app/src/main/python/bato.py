"""
Ridibooks & Naver & XToon Downloader & Queue Manager for AstralStitch.
"""

import json
import os
import re
import shutil
import time
import uuid
import fcntl
import requests
import urllib3
from PIL import Image
from io import BytesIO
from pathlib import Path
from urllib.parse import urlparse, urlunparse
from typing import List, Dict, Optional, Tuple

import SmartStitchCore as ssc
import bridge
import naver_downloader
import xtoon_downloader
import kakaopage_downloader
import mangago_downloader

# Import Java helper for WebP conversion
try:
    from java import jclass
    MainActivity = jclass("com.astral.stitchapp.MainActivity")
except ImportError:
    MainActivity = None

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# Suppress SSL warnings for MangaGo
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# ============================================================
# UTILS & LOCKING
# ============================================================

class FileLock:
    def __init__(self, lock_file):
        self.lock_file = lock_file
        self.fd = None

    def __enter__(self):
        self.fd = open(self.lock_file, 'w')
        fcntl.flock(self.fd, fcntl.LOCK_EX)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        fcntl.flock(self.fd, fcntl.LOCK_UN)
        self.fd.close()

def sanitize_filename(name: str) -> str:
    return re.sub(r"[\\/:*?\"<>|]", "_", name).strip()

# ============================================================
# DOWNLOADING HELPERS
# ============================================================

def fetch_html(url: str, cookie: str = None) -> str:
    headers = {"User-Agent": USER_AGENT}
    if cookie:
        headers["Cookie"] = cookie

    response = requests.get(url, headers=headers, timeout=30)
    response.raise_for_status()
    # Handle encoding
    response.encoding = response.apparent_encoding
    return response.text

def normalize_image_host(url: str) -> str:
    p = urlparse(url)
    if p.netloc.startswith("k"):
        return urlunparse(p._replace(netloc="n" + p.netloc[1:]))
    return url

def clean_title_text(text: str) -> str:
    text = text.replace(" - Read Free Manga Online", "")
    text = text.replace("Read Free Manga Online", "")
    text = text.replace(" - Ridibooks", "")
    text = re.sub(r"\[.*?\]$", "", text)
    return text.strip()

def extract_title_from_html(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE)
    if m:
        t = clean_title_text(m.group(1))
        return sanitize_filename(t)
    return ""

def unscramble_mangago_image(path: Path, desckey: str, cols: int):
    try:
        with Image.open(path) as img:
            img = img.convert("RGBA")
            width, height = img.size
            result = Image.new("RGBA", (width, height))

            unit_width = width // cols
            unit_height = height // cols

            if "a" in desckey:
                key_array = desckey.split("a")
            else:
                key_array = desckey.split(",")

            for idx in range(cols * cols):
                keyval_str = key_array[idx] if idx < len(key_array) else "0"
                keyval = int(keyval_str) if keyval_str else 0

                # Source coordinates from keyval
                sx_idx = keyval % cols
                sy_idx = keyval // cols
                sx = sx_idx * unit_width
                sy = sy_idx * unit_height

                # Destination coordinates from idx
                dx_idx = idx % cols
                dy_idx = idx // cols
                dx = dx_idx * unit_width
                dy = dy_idx * unit_height

                # box is (left, top, right, bottom)
                src_box = (sx, sy, sx + unit_width, sy + unit_height)
                tile = img.crop(src_box)
                result.paste(tile, (dx, dy))

            # Save back as JPEG to match expected output of MangaGo usually
            # But preserve extension if it was something else?
            # Actually MangaGo usually serves JPEGs.
            ext = path.suffix.lower()
            if ext in [".jpg", ".jpeg"]:
                result.convert("RGB").save(path, "JPEG", quality=100)
            elif ext == ".png":
                result.save(path, "PNG")
            elif ext == ".webp":
                result.save(path, "WEBP", quality=100)
            else:
                result.convert("RGB").save(path, "JPEG", quality=100)
    except Exception as e:
        print(f"Unscramble failed for {path}: {e}")

def download_image(url: str, dest: Path, idx: int, session: requests.Session, cookie: str = None, referer: str = None):
    # Parse fragment for unscrambling
    p_url = urlparse(url)
    fragment = p_url.fragment
    desckey = None
    cols = None
    if fragment:
        params = dict(item.split("=") for item in fragment.split("&") if "=" in item)
        desckey = params.get("desckey")
        cols = params.get("cols")
        if cols: cols = int(cols)

    # Strip fragment from URL for actual request
    clean_url = urlunparse(p_url._replace(fragment=""))

    url = normalize_image_host(clean_url)
    path = urlparse(url).path
    _, ext = os.path.splitext(path)
    if not ext: ext = ".jpg"

    name = f"img_{idx:04d}{ext}"
    target = dest / name

    # Skip if exists and size > 0 (Resume support)
    if target.exists() and target.stat().st_size > 0:
        # If it was already downloaded, we still might need to unscramble it
        # if the previous run was interrupted. But usually we'd assume it's done.
        return

    headers = {"User-Agent": USER_AGENT}
    if referer: headers["Referer"] = referer
    if cookie: headers["Cookie"] = cookie

    for attempt in range(3):
        try:
            with session.get(url, headers=headers, timeout=30, stream=True, verify=False) as r:
                r.raise_for_status()
                with open(target, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        f.write(chunk)

            # Fix extension if needed
            target = Path(ssc.fix_image_extension(target))

            # Apply unscrambling if needed
            if desckey and cols:
                unscramble_mangago_image(target, desckey, cols)

            return
        except Exception as e:
            if attempt == 2:
                print(f"Failed to download {url}: {e}")
                raise


# ============================================================
# RIDIBOOKS LOGIC
# ============================================================

RIDI_API_URL = 'https://ridibooks.com/api/web-viewer/generate'

def get_ridi_book_id(url: str) -> str:
    # Pattern: /books/5946000015/... or /webtoon/5946000015/...
    m = re.search(r"/(?:books|webtoon)/(\d{6,})", url)
    if m:
        return m.group(1)
    return ""

def fetch_ridi_data(book_id: str, cookie: str) -> Dict:
    headers = {
        "User-Agent": USER_AGENT,
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Referer": f"https://ridibooks.com/books/{book_id}/view"
    }
    if cookie:
        headers["Cookie"] = cookie

    data = json.dumps({"book_id": str(book_id)})
    try:
        response = requests.post(RIDI_API_URL, data=data, headers=headers, timeout=30)
        response.raise_for_status()
        return response.json()
    except Exception as e:
        # Mimic urllib error handling slightly or just propagate
        raise RuntimeError(f"Ridi Fetch Error: {e}")

def process_ridi_item(book_id: str, cookie: str) -> Tuple[str, List[str]]:
    # Returns (Title, List of Image URLs)
    data = fetch_ridi_data(book_id, cookie)
    if not data.get("success"):
        raise RuntimeError(f"Ridi API returned success=False. Msg: {data.get('message', 'Unknown')}")

    pages = data.get("data", {}).get("pages", [])
    if not pages:
        raise RuntimeError("No pages found in Ridi response")

    images = [p["src"] for p in pages if "src" in p]

    return "", images

# ============================================================
# QUEUE SYSTEM
# ============================================================

class BatoQueue:
    def __init__(self, storage_path: str):
        self.storage_path = Path(storage_path)
        self.queue_file = self.storage_path / "queue.json"
        self.lock_path = self.storage_path / "queue.lock"
        self._ensure_storage()

    def _ensure_storage(self):
        self.storage_path.mkdir(parents=True, exist_ok=True)
        if not self.queue_file.exists():
            with FileLock(self.lock_path):
                with open(self.queue_file, "w") as f:
                    json.dump([], f)

    def _load(self) -> List[Dict]:
        try:
            with open(self.queue_file, "r") as f:
                return json.load(f)
        except:
            return []

    def _save(self, queue: List[Dict]):
        with open(self.queue_file, "w") as f:
            json.dump(queue, f, indent=2)

    def add_url(self, url: str, source_type: str = "ridi", cookie: str = "") -> Dict:
        # source_type: "ridi", "naver", "xtoon"

        with FileLock(self.lock_path):
            queue = self._load()
            added = 0

            try:
                if source_type == "ridi":
                    book_id = get_ridi_book_id(url)
                    if not book_id:
                        return {"error": "Invalid Ridi URL (No Book ID found)"}

                    title = f"Ridi Book {book_id}"
                    try:
                        html = fetch_html(url, cookie=cookie)
                        extracted = extract_title_from_html(html)
                        if extracted:
                            title = extracted
                    except Exception as e:
                        print(f"Failed to fetch Ridi title: {e}")

                    if not any(q['url'] == url for q in queue):
                        queue.append({
                            "id": str(uuid.uuid4()),
                            "url": url,
                            "title": title,
                            "status": "pending",
                            "added_at": time.time(),
                            "type": "ridi",
                            "cookie": cookie
                        })
                        added = 1

                elif source_type == "naver":
                    # url is now the full URL (detail page with titleId and no)
                    info = naver_downloader.get_naver_chapter_info(url)
                    if "error" in info:
                        return info

                    title = info.get("title", "Naver Chapter")
                    if not any(q['url'] == url for q in queue):
                         queue.append({
                            "id": str(uuid.uuid4()),
                            "url": url,
                            "title": title,
                            "status": "pending",
                            "added_at": time.time(),
                            "type": "naver"
                         })
                         added = 1

                elif source_type == "xtoon":
                    title = xtoon_downloader.get_xtoon_title(url)
                    if not any(q['url'] == url for q in queue):
                        queue.append({
                            "id": str(uuid.uuid4()),
                            "url": url,
                            "title": title,
                            "status": "pending",
                            "added_at": time.time(),
                            "type": "xtoon"
                        })
                        added = 1

                elif source_type == "kakao":
                    info = kakaopage_downloader.get_chapter_info(url, cookie)
                    title = info.get("title", "KakaoPage Item")

                    if not any(q['url'] == url for q in queue):
                        queue.append({
                            "id": str(uuid.uuid4()),
                            "url": url,
                            "title": title,
                            "status": "pending",
                            "added_at": time.time(),
                            "type": "kakao",
                            "cookie": cookie
                        })
                        added = 1

                elif source_type == "mangago":
                    info = mangago_downloader.get_chapter_info(url, cookie)
                    title = info.get("title", "MangaGo Item")

                    if not any(q['url'] == url for q in queue):
                        queue.append({
                            "id": str(uuid.uuid4()),
                            "url": url,
                            "title": title,
                            "status": "pending",
                            "added_at": time.time(),
                            "type": "mangago",
                            "cookie": cookie
                        })
                        added = 1

                if added > 0:
                    self._save(queue)
            except Exception as e:
                return {"error": str(e)}
        return {"added": added}

    def add_direct_job(self, title: str, images: List[str], cookie: str, source_type: str) -> Dict:
        with FileLock(self.lock_path):
            queue = self._load()

            # Use a fake URL to identify direct jobs if needed, but ensure uniqueness
            fake_url = f"direct://{sanitize_filename(title)}/{int(time.time())}"

            queue.append({
                "id": str(uuid.uuid4()),
                "url": fake_url,
                "title": title,
                "status": "pending",
                "added_at": time.time(),
                "type": source_type,
                "cookie": cookie,
                "pre_scraped_images": images
            })
            self._save(queue)
            return {"added": 1}

    def get_queue(self) -> str:
        with FileLock(self.lock_path):
            return json.dumps(self._load())

    def get_and_lock_next_pending(self) -> Optional[Dict]:
        with FileLock(self.lock_path):
            queue = self._load()
            for item in queue:
                if item["status"] == "pending":
                    item["status"] = "initializing"
                    self._save(queue)
                    return item
        return None

    def update_status(self, item_id: str, status: str, progress: float = 0.0):
        with FileLock(self.lock_path):
            queue = self._load()
            for item in queue:
                if item["id"] == item_id:
                    if item["status"] == "paused" and status != "paused":
                        pass # Respect user pause
                    item["status"] = status
                    item["progress"] = progress
                    break
            self._save(queue)

    def remove_item(self, item_id: str):
        with FileLock(self.lock_path):
            queue = self._load()
            queue = [q for q in queue if q["id"] != item_id]
            self._save(queue)

    def retry_item(self, item_id: str):
        with FileLock(self.lock_path):
            queue = self._load()
            for item in queue:
                if item["id"] == item_id:
                    item["status"] = "pending"
                    item["progress"] = 0.0
                    break
            self._save(queue)

    def pause_item(self, item_id: str):
        with FileLock(self.lock_path):
            queue = self._load()
            for item in queue:
                if item["id"] == item_id:
                    item["status"] = "paused"
                    break
            self._save(queue)

    def check_action(self, item_id: str) -> str:
        with FileLock(self.lock_path):
            queue = self._load()
            item = next((x for x in queue if x["id"] == item_id), None)
            if not item: return "removed"
            if item["status"] == "paused": return "paused"
            return "ok"

    def clear_completed(self):
        with FileLock(self.lock_path):
            queue = self._load()
            queue = [q for q in queue if q["status"] != "done"]
            self._save(queue)

# ============================================================
# PROCESSING WORKER
# ============================================================

def process_item(item_id: str, cache_dir: str, stitch_params_json: str):
    params = json.loads(stitch_params_json)
    auto_retry = params.get("autoRetry", True)
    queue_mgr = BatoQueue(cache_dir)

    with FileLock(queue_mgr.lock_path):
        queue = queue_mgr._load()
        item = next((x for x in queue if x["id"] == item_id), None)

    if not item:
        return json.dumps({"error": "Item not found"})

    url = item["url"]
    source_type = item.get("type", "ridi")
    base_dir = Path(cache_dir)

    queue_mgr.update_status(item_id, "downloading", 0.0)

    dl_dir = base_dir / "temp_dl" / item_id
    output_parent = base_dir / "temp_out" / item_id

    # Use title for folder, but ensure unique
    title_safe = sanitize_filename(item["title"])
    output_dir = output_parent / title_safe

    try:
        images = []
        referer = None

        if source_type == "ridi":
            book_id = get_ridi_book_id(url)
            cookie = item.get("cookie", "")
            title_prefix, imgs = process_ridi_item(book_id, cookie)
            images = imgs
            referer = f"https://ridibooks.com/books/{book_id}/view"

        elif source_type == "naver":
            images = naver_downloader.get_naver_images(url)
            referer = "https://comic.naver.com/"

        elif source_type == "xtoon":
            images = xtoon_downloader.get_xtoon_images(url)
            referer = url

        elif source_type == "kakao":
            images = kakaopage_downloader.get_images(url, item.get("cookie"))
            referer = "https://page.kakao.com/"

        elif source_type == "mangago":
            if "pre_scraped_images" in item:
                images = item["pre_scraped_images"]
            else:
                images = mangago_downloader.get_images(url, item.get("cookie"))
            referer = "https://www.mangago.zone/"

        if len(images) == 0:
            raise Exception("No images found")

        dl_dir.mkdir(parents=True, exist_ok=True)

        total = len(images)

        # Use a session for the loop
        with requests.Session() as session:
            for i, img in enumerate(images, 1):
                action = queue_mgr.check_action(item_id)
                if action == "paused":
                    return json.dumps({"status": "paused"})
                if action == "removed":
                    if dl_dir.exists(): shutil.rmtree(dl_dir, ignore_errors=True)
                    return json.dumps({"status": "removed"})

                download_image(img, dl_dir, i, session=session, cookie=item.get("cookie"), referer=referer)

                if i % 5 == 0:
                    queue_mgr.update_status(item_id, "downloading", i/total)

        downloaded_files = list(dl_dir.iterdir())

        # Native WebP Conversion
        if MainActivity:
            for f in downloaded_files:
                if f.suffix.lower() == ".webp":
                    try:
                        success = MainActivity.convertWebpToPng(str(f.absolute()))
                        if success: f.unlink()
                    except: pass

        # Verify integrity
        final_files = [f for f in dl_dir.iterdir() if f.is_file()]
        if len(final_files) < len(images):
            raise Exception(f"Incomplete download: Expected {len(images)}, got {len(final_files)}")

        queue_mgr.update_status(item_id, "stitching", 0.0)

        if output_parent.exists(): shutil.rmtree(output_parent)
        output_dir.mkdir(parents=True, exist_ok=True)

        final_path = bridge.run(
            input_folder=str(dl_dir),
            output_folder=str(output_dir),
            split_height=int(params.get("splitHeight", 5000)),
            output_files_type=params.get("outputType", ".png"),
            batch_mode=False,
            width_enforce_type=int(params.get("widthEnforce", 0)),
            custom_width=int(params.get("customWidth", 720)),
            senstivity=int(params.get("sensitivity", 90)),
            ignorable_pixels=int(params.get("ignorable", 0)),
            scan_line_step=int(params.get("scanStep", 5)),
            low_ram=bool(params.get("lowRam", False)),
            unit_images=20,
            zip_output=params.get("packaging") == "ZIP",
            pdf_output=params.get("packaging") == "PDF",
            mark_done=False,
            split_mode=int(params.get("splitMode", 0)),
            quality=int(params.get("quality", 100))
        )

        shutil.rmtree(dl_dir, ignore_errors=True)
        queue_mgr.update_status(item_id, "done", 1.0)

        return json.dumps({
            "status": "success",
            "path": str(final_path),
            "title": item["title"]
        })

    except Exception as e:
        print(f"Error processing {item_id}: {e}")

        new_status = "failed"
        if auto_retry:
             with FileLock(queue_mgr.lock_path):
                queue = queue_mgr._load()
                for q in queue:
                    if q["id"] == item_id:
                        retries = q.get("retry_count", 0)
                        if retries < 3:
                            q["retry_count"] = retries + 1
                            q["status"] = "pending"
                            new_status = "pending"
                        else:
                            q["status"] = "failed"
                        break
                queue_mgr._save(queue)
        else:
            queue_mgr.update_status(item_id, "failed", 0.0)

        return json.dumps({"error": str(e), "status": new_status})

# ============================================================
# API
# ============================================================

def get_queue(cache_dir: str) -> str:
    return BatoQueue(cache_dir).get_queue()

def add_to_queue(cache_dir: str, url: str, source_type: str = "ridi", cookie: str = "") -> str:
    return json.dumps(BatoQueue(cache_dir).add_url(url, source_type, cookie))

def add_direct_job(cache_dir: str, title: str, images: List[str], cookie: str = "", source_type: str = "mangago") -> str:
    return json.dumps(BatoQueue(cache_dir).add_direct_job(title, list(images), cookie, source_type))

def remove_from_queue(cache_dir: str, item_id: str):
    BatoQueue(cache_dir).remove_item(item_id)

def retry_item(cache_dir: str, item_id: str):
    BatoQueue(cache_dir).retry_item(item_id)

def pause_item(cache_dir: str, item_id: str):
    BatoQueue(cache_dir).pause_item(item_id)

def clear_completed(cache_dir: str):
    BatoQueue(cache_dir).clear_completed()

def process_next_item(cache_dir: str, stitch_params_json: str) -> str:
    bq = BatoQueue(cache_dir)
    item = bq.get_and_lock_next_pending()
    if not item:
        return json.dumps({"status": "empty"})
    return process_item(item["id"], cache_dir, stitch_params_json)
