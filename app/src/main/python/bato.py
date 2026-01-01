"""
Bato Downloader & Queue Manager for AstralStitch.
"""

import json
import os
import re
import shutil
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen
from typing import List, Dict, Optional, Tuple

import SmartStitchCore as ssc
import bridge

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# ============================================================
# CORE DOWNLOADER LOGIC (Adapted from bato-chapter.py)
# ============================================================

def normalize_bato_url(url: str) -> str:
    p = urlparse(url)
    path = p.path.rstrip("/")

    # Handle /chapter/123 -> /title/_/123
    m = re.match(r"^/chapter/(\d+)$", path)
    if m:
        return f"https://bato.ing/title/_/{m.group(1)}"

    # Handle /title/...
    if path.startswith("/title/"):
        return f"https://bato.ing{path}"

    return f"https://bato.ing{path}"

def fetch_html(url: str) -> str:
    req = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Referer": "https://bato.ing/",
        },
    )
    with urlopen(req, timeout=30) as r:
        charset = r.headers.get_content_charset() or "utf-8"
        return r.read().decode(charset, errors="replace")

IMG_URL_RE = re.compile(r"https://[kn]\d+\.mb[^\"'\s<>]+", re.IGNORECASE)

def extract_images(html: str) -> List[str]:
    found = IMG_URL_RE.findall(html)
    if not found:
        return []

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

def extract_title(html: str) -> str:
    # Try <title>
    m = re.search(r"<title>(.*?)</title>", html, re.IGNORECASE)
    if m:
        t = m.group(1).replace("Bato.to", "").replace("Bato.ing", "").strip()
        return sanitize_filename(t) or "bato_chapter"
    return "bato_chapter"

def download_image(url: str, dest: Path, idx: int):
    url = normalize_image_host(url)
    path = urlparse(url).path
    _, ext = os.path.splitext(path)
    if not ext: ext = ".jpg"

    name = f"img_{idx:04d}{ext}"
    target = dest / name

    # Simple retry loop
    for attempt in range(3):
        try:
            req = Request(
                url,
                headers={"User-Agent": USER_AGENT, "Referer": "https://bato.ing/"},
            )
            with urlopen(req, timeout=30) as r:
                target.write_bytes(r.read())
            return
        except Exception:
            if attempt == 2:
                raise

# ============================================================
# SERIES PARSER
# ============================================================

def fetch_series_chapters(url: str) -> List[Dict[str, str]]:
    """
    Returns list of dicts: {"url": "...", "title": "..."}
    """
    html = fetch_html(url)

    # Extract Series Title
    series_title = "Unknown Series"
    m_title = re.search(r"<title>(.*?)</title>", html, re.IGNORECASE)
    if m_title:
        # Format: "Title [Group] - Read Free Manga Online"
        raw_t = m_title.group(1)
        series_title = raw_t.split("- Read")[0].strip()
        series_title = sanitize_filename(series_title)

    chapters = []
    seen_urls = set()

    # Bato v3/v4 structure:
    # Look for href="/title/..."

    link_re = re.compile(r"href=\"(/title/[^\"]+)\"", re.IGNORECASE)

    for m in link_re.finditer(html):
        href = m.group(1)

        # Filter: Must have at least 3 slashes? /title/series/chapter
        parts = [p for p in href.split("/") if p]
        if len(parts) < 3: continue

        # Check if it looks like a chapter (has number ID at start of parts[2])
        if not re.match(r"^\d+", parts[2]): continue

        full_url = "https://bato.ing" + href

        if full_url not in seen_urls:
            # Simple extraction from slug: "3035895-vol_1-ch_1" -> "vol_1-ch_1"
            slug = parts[2]
            slug_parts = slug.split("-", 1)
            ch_name = slug_parts[1] if len(slug_parts) > 1 else slug
            ch_name = ch_name.replace("_", " ").title()

            # Construct a specific regex for this href to find the link text
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

    # Reverse to download from Ch 1 to Ch N (HTML usually lists newest first)
    return list(reversed(chapters))

def is_series_url(url: str) -> bool:
    if "/chapter/" in url: return False

    # /title/ID-slug
    # /title/ID-slug/ID-slug -> Chapter
    parts = [p for p in urlparse(url).path.split("/") if p]
    if len(parts) >= 3:
        # title, series_id, chapter_id
        return False
    return True

# ============================================================
# QUEUE SYSTEM
# ============================================================

class BatoQueue:
    def __init__(self, storage_path: str):
        self.storage_path = Path(storage_path)
        self.queue_file = self.storage_path / "queue.json"
        self._ensure_storage()

    def _ensure_storage(self):
        self.storage_path.mkdir(parents=True, exist_ok=True)
        if not self.queue_file.exists():
            self._save([])

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
        """
        Analyzes URL. If series, adds all chapters. If chapter, adds one.
        Returns summary dict.
        """
        url = normalize_bato_url(url)
        queue = self._load()
        added = 0

        try:
            if is_series_url(url):
                chapters = fetch_series_chapters(url)
                for ch in chapters:
                    # Check duplicates
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
                # Single chapter
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
        except Exception as e:
            return {"error": str(e)}

        self._save(queue)
        return {"added": added}

    def get_queue(self) -> str:
        return json.dumps(self._load())

    def get_next_pending(self) -> Optional[Dict]:
        queue = self._load()
        for item in queue:
            if item["status"] == "pending":
                return item
        return None

    def update_status(self, item_id: str, status: str, progress: float = 0.0):
        queue = self._load()
        for item in queue:
            if item["id"] == item_id:
                item["status"] = status
                item["progress"] = progress
                break
        self._save(queue)

    def remove_item(self, item_id: str):
        queue = self._load()
        queue = [q for q in queue if q["id"] != item_id]
        self._save(queue)

    def clear_completed(self):
        queue = self._load()
        queue = [q for q in queue if q["status"] != "done"]
        self._save(queue)


# ============================================================
# PROCESSING WORKER
# ============================================================

def process_item(item_id: str, cache_dir: str, stitch_params_json: str):
    """
    Orchestrates: Download -> Stitch -> Return Result Path
    """
    params = json.loads(stitch_params_json)
    queue_mgr = BatoQueue(cache_dir)

    queue = queue_mgr._load()
    item = next((x for x in queue if x["id"] == item_id), None)
    if not item:
        return json.dumps({"error": "Item not found"})

    url = item["url"]
    base_dir = Path(cache_dir)

    # 1. Download
    queue_mgr.update_status(item_id, "downloading", 0.0)

    try:
        normalized_url = normalize_bato_url(url)
        html = fetch_html(normalized_url)
        title = extract_title(html)
        images = extract_images(html)

        if not images:
            raise RuntimeError("No images found")

        dl_dir = base_dir / "temp_dl" / item_id
        if dl_dir.exists(): shutil.rmtree(dl_dir)
        dl_dir.mkdir(parents=True, exist_ok=True)

        total = len(images)
        for i, img in enumerate(images, 1):
            download_image(img, dl_dir, i)
            if i % 5 == 0:
                queue_mgr.update_status(item_id, "downloading", i/total)

        queue_mgr.update_status(item_id, "stitching", 0.0)

        # 2. Stitch
        output_dir = base_dir / "temp_out" / sanitize_filename(title)
        if output_dir.exists(): shutil.rmtree(output_dir)
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

        # 3. Cleanup DL
        shutil.rmtree(dl_dir, ignore_errors=True)

        queue_mgr.update_status(item_id, "done", 1.0)

        return json.dumps({
            "status": "success",
            "path": str(final_path),
            "title": title
        })

    except Exception as e:
        queue_mgr.update_status(item_id, "failed", 0.0)
        return json.dumps({"error": str(e)})


# ============================================================
# EXPOSED API FOR ANDROID
# ============================================================

def get_queue(cache_dir: str) -> str:
    return BatoQueue(cache_dir).get_queue()

def add_to_queue(cache_dir: str, url: str) -> str:
    return json.dumps(BatoQueue(cache_dir).add_url(url))

def remove_from_queue(cache_dir: str, item_id: str):
    BatoQueue(cache_dir).remove_item(item_id)

def clear_completed(cache_dir: str):
    BatoQueue(cache_dir).clear_completed()

def process_next_item(cache_dir: str, stitch_params_json: str) -> str:
    bq = BatoQueue(cache_dir)
    item = bq.get_next_pending()
    if not item:
        return json.dumps({"status": "empty"})

    return process_item(item["id"], cache_dir, stitch_params_json)

# Legacy support for existing Stitch Tab (if needed)
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
        for i, img in enumerate(images, 1):
            download_image(img, out, i)
            if progress_path:
                 with open(progress_path, "w") as f:
                    json.dump({"processed": start + i, "total": start + total + extra_total}, f)

        return json.dumps({"path": str(out), "count": total})
    except Exception as e:
        raise RuntimeError(str(e))
