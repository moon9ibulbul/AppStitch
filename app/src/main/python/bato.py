"""
Bato Downloader & Queue Manager for AstralStitch.
"""

import json
import os
import re
import shutil
import time
import uuid
import fcntl
from pathlib import Path
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen
from typing import List, Dict, Optional, Tuple

import SmartStitchCore as ssc
import bridge

# Import Java helper for WebP conversion
try:
    from java import jclass
    MainActivity = jclass("com.astral.stitchapp.MainActivity")
except ImportError:
    MainActivity = None

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

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

# ============================================================
# CORE DOWNLOADER LOGIC
# ============================================================

def normalize_bato_url(url: str) -> str:
    p = urlparse(url)
    path = p.path.rstrip("/")
    m = re.match(r"^/chapter/(\d+)$", path)
    if m:
        return f"https://bato.ing/title/_/{m.group(1)}"
    if path.startswith("/title/"):
        return f"https://bato.ing{path}"
    return f"https://bato.ing{path}"

def fetch_html(url: str) -> str:
    req = Request(url, headers={"User-Agent": USER_AGENT, "Referer": "https://bato.ing/"})
    with urlopen(req, timeout=30) as r:
        charset = r.headers.get_content_charset() or "utf-8"
        return r.read().decode(charset, errors="replace")

IMG_URL_RE = re.compile(r"https://[kn]\d+\.mb[^\"'\s<>]+", re.IGNORECASE)

def extract_images(html: str) -> List[str]:
    found = IMG_URL_RE.findall(html)
    seen = set()
    images = []
    for url in found:
        if url not in seen:
            seen.add(url)
            images.append(url)
    return images

def normalize_image_host(url: str) -> str:
    p = urlparse(url)
    if p.netloc.startswith("k"):
        return urlunparse(p._replace(netloc="n" + p.netloc[1:]))
    return url

def sanitize_filename(name: str) -> str:
    return re.sub(r"[\\/:*?\"<>|]", "_", name).strip()

def clean_title_text(text: str) -> str:
    text = text.replace(" - Read Free Manga Online", "")
    text = text.replace("Read Free Manga Online", "")
    text = text.replace("Bato.to", "").replace("Bato.ing", "")
    text = re.sub(r"\[.*?\]$", "", text)
    return text.strip()

def extract_title(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE)
    if m:
        t = clean_title_text(m.group(1))
        return sanitize_filename(t) or "bato_chapter"
    return "bato_chapter"

def download_image(url: str, dest: Path, idx: int):
    url = normalize_image_host(url)
    path = urlparse(url).path
    _, ext = os.path.splitext(path)
    if not ext: ext = ".jpg"

    name = f"img_{idx:04d}{ext}"
    target = dest / name

    for attempt in range(3):
        try:
            req = Request(url, headers={"User-Agent": USER_AGENT, "Referer": "https://bato.ing/"})
            with urlopen(req, timeout=30) as r:
                content = r.read()
                if len(content) == 0: raise IOError("Empty content")
                target.write_bytes(content)
            return
        except Exception as e:
            if attempt == 2:
                print(f"Failed to download {url}: {e}")
                raise

# ============================================================
# SERIES PARSER
# ============================================================

def fetch_series_chapters(url: str) -> List[Dict[str, str]]:
    html = fetch_html(url)
    series_title = "Unknown Series"
    m_title = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE)
    if m_title:
        series_title = clean_title_text(m_title.group(1))

    chapters = []
    seen_urls = set()
    link_re = re.compile(r"href=\"(/title/[^\"]+)\"", re.IGNORECASE)

    for m in link_re.finditer(html):
        href = m.group(1)
        parts = [p for p in href.split("/") if p]
        if len(parts) < 3: continue
        if not re.match(r"^\d+", parts[2]): continue

        full_url = "https://bato.ing" + href
        if full_url not in seen_urls:
            slug = parts[2]
            slug_parts = slug.split("-", 1)
            ch_name = slug_parts[1] if len(slug_parts) > 1 else slug
            ch_name = ch_name.replace("_", " ").title()

            safe_href = re.escape(href)
            text_re = re.search(fr"href=\"{safe_href}\"[^>]*>(.*?)</a>", html, re.DOTALL | re.IGNORECASE)
            if text_re:
                raw_text = text_re.group(1)
                clean_text = re.sub(r"<[^>]+>", " ", raw_text)
                clean_text = re.sub(r"\s+", " ", clean_text).strip()
                if clean_text:
                    ch_name = clean_text

            seen_urls.add(full_url)
            chapters.append({
                "url": full_url,
                "title": f"{series_title} - {ch_name}"
            })

    return list(reversed(chapters))

def is_series_url(url: str) -> bool:
    if "/chapter/" in url: return False
    parts = [p for p in urlparse(url).path.split("/") if p]
    if len(parts) >= 3: return False
    return True

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

    def add_url(self, url: str) -> Dict:
        url = normalize_bato_url(url)
        with FileLock(self.lock_path):
            queue = self._load()
            added = 0
            try:
                if is_series_url(url):
                    chapters = fetch_series_chapters(url)
                    for ch in chapters:
                        if not any(q['url'] == ch['url'] for q in queue):
                            queue.append({
                                "id": str(uuid.uuid4()),
                                "url": ch['url'],
                                "title": ch['title'],
                                "status": "pending",
                                "added_at": time.time()
                            })
                            added += 1
                else:
                    html = fetch_html(url)
                    title = extract_title(html)
                    if not any(q['url'] == url for q in queue):
                        queue.append({
                            "id": str(uuid.uuid4()),
                            "url": url,
                            "title": title,
                            "status": "pending",
                            "added_at": time.time()
                        })
                        added = 1
                if added > 0:
                    self._save(queue)
            except Exception as e:
                return {"error": str(e)}
        return {"added": added}

    def get_queue(self) -> str:
        with FileLock(self.lock_path):
            return json.dumps(self._load())

    def get_and_lock_next_pending(self) -> Optional[Dict]:
        with FileLock(self.lock_path):
            queue = self._load()
            for item in queue:
                if item["status"] == "pending":
                    item["status"] = "initializing" # Mark as taken
                    self._save(queue)
                    return item
        return None

    def update_status(self, item_id: str, status: str, progress: float = 0.0):
        with FileLock(self.lock_path):
            queue = self._load()
            for item in queue:
                if item["id"] == item_id:
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
    queue_mgr = BatoQueue(cache_dir)

    # We don't fetch item again from queue because we might not have lock
    # But status update needs it.

    # Actually we need the URL and Title.
    # get_and_lock returned it, but this function is called separately (by thread).
    # Android calls process_next_item which calls this.
    # No, Android calls process_next_item which gets the item.

    # Let's fix process_next_item to do the lookup.

    # Wait, process_item needs to find the item in queue to get URL.
    # We updated it to 'initializing', so we can find it.
    with FileLock(queue_mgr.lock_path):
        queue = queue_mgr._load()
        item = next((x for x in queue if x["id"] == item_id), None)

    if not item:
        return json.dumps({"error": "Item not found"})

    url = item["url"]
    base_dir = Path(cache_dir)

    queue_mgr.update_status(item_id, "downloading", 0.0)

    # Unique DL dir
    dl_dir = base_dir / "temp_dl" / item_id

    # Unique Output dir structure to support concurrency
    # .../temp_out/{item_id}/{Title}
    # This ensures no collision if multiple items have same Title.
    output_parent = base_dir / "temp_out" / item_id
    output_dir = output_parent / sanitize_filename(item["title"])

    try:
        normalized_url = normalize_bato_url(url)
        html = fetch_html(normalized_url)
        title = item["title"]
        images = extract_images(html)

        if not images:
            raise RuntimeError("No images found")

        if dl_dir.exists(): shutil.rmtree(dl_dir)
        dl_dir.mkdir(parents=True, exist_ok=True)

        total = len(images)
        for i, img in enumerate(images, 1):
            download_image(img, dl_dir, i)
            if i % 5 == 0:
                queue_mgr.update_status(item_id, "downloading", i/total)

        downloaded_files = list(dl_dir.iterdir())
        if len(downloaded_files) < len(images):
             raise RuntimeError(f"Download incomplete: {len(downloaded_files)}/{len(images)}")

        # Native WebP Conversion via Android
        if MainActivity:
            for f in downloaded_files:
                if f.suffix.lower() == ".webp":
                    try:
                        success = MainActivity.convertWebpToPng(str(f.absolute()))
                        if success:
                            f.unlink() # Delete webp
                        else:
                            print(f"Native conversion failed for {f}")
                    except Exception as e:
                        print(f"Error calling native conversion: {e}")

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
            low_ram=params.get("lowRam", False),
            unit_images=int(params.get("unitImages", 20)),
            zip_output=params.get("packaging") == "ZIP",
            pdf_output=params.get("packaging") == "PDF",
            mark_done=False
        )

        # Cleanup
        shutil.rmtree(dl_dir, ignore_errors=True)

        queue_mgr.update_status(item_id, "done", 1.0)

        return json.dumps({
            "status": "success",
            "path": str(final_path),
            "title": title
        })

    except Exception as e:
        queue_mgr.update_status(item_id, "failed", 0.0)
        if dl_dir.exists(): shutil.rmtree(dl_dir, ignore_errors=True)
        if output_parent.exists(): shutil.rmtree(output_parent, ignore_errors=True)
        return json.dumps({"error": str(e)})

# ============================================================
# API
# ============================================================

def get_queue(cache_dir: str) -> str:
    return BatoQueue(cache_dir).get_queue()

def add_to_queue(cache_dir: str, url: str) -> str:
    return json.dumps(BatoQueue(cache_dir).add_url(url))

def remove_from_queue(cache_dir: str, item_id: str):
    BatoQueue(cache_dir).remove_item(item_id)

def retry_item(cache_dir: str, item_id: str):
    BatoQueue(cache_dir).retry_item(item_id)

def clear_completed(cache_dir: str):
    BatoQueue(cache_dir).clear_completed()

def process_next_item(cache_dir: str, stitch_params_json: str) -> str:
    bq = BatoQueue(cache_dir)
    item = bq.get_and_lock_next_pending()
    if not item:
        return json.dumps({"status": "empty"})
    return process_item(item["id"], cache_dir, stitch_params_json)

def download_bato(url: str, output_dir: str, progress_path: Optional[str] = None,
                  start: int = 0, extra_total: int = 0) -> str:
    try:
        normalized = normalize_bato_url(url)
        html = fetch_html(normalized)
        images = extract_images(html)
        title = extract_title(html)

        out = Path(output_dir) / sanitize_filename(title)
        out.mkdir(parents=True, exist_ok=True)

        total = len(images)
        if total == 0: raise RuntimeError("No images found")

        for i, img in enumerate(images, 1):
            download_image(img, out, i)
            if progress_path:
                 with open(progress_path, "w") as f:
                    json.dump({"processed": start + i, "total": start + total + extra_total}, f)

        # Native Conversion for legacy call
        if MainActivity:
            for f in out.iterdir():
                if f.suffix.lower() == ".webp":
                    try:
                        success = MainActivity.convertWebpToPng(str(f.absolute()))
                        if success: f.unlink()
                    except: pass

        return json.dumps({"path": str(out), "count": total})
    except Exception as e:
        raise RuntimeError(str(e))
