# core/downloader.py

import os
import requests
import concurrent.futures
from tqdm import tqdm
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from utils.logger import logger
from utils.helpers import create_directory, sanitize_filename
from utils.config import OUTPUT_DIR

def create_session():
    """Create a requests session with retry logic."""
    session = requests.Session()
    retry = Retry(
        total=5,
        read=5,
        connect=5,
        backoff_factor=0.3,
        status_forcelist=(500, 502, 504)
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount('http://', adapter)
    session.mount('https://', adapter)
    return session

session = create_session()

def download_image(args):
    """Download a single image."""
    image_url, chapter_dir, i = args
    try:
        response = session.get(image_url, headers={'Referer': 'https://www.webtoons.com'}, timeout=10)
        response.raise_for_status()

        image_path = os.path.join(chapter_dir, f"{i:03d}.jpg")

        with open(image_path, "wb") as f:
            f.write(response.content)

        return image_path
    except requests.exceptions.RequestException as e:
        logger.error(f"Failed to download {image_url}: {e}")
        return None

def download_chapter(manga_title, episode_number, image_urls, num_threads=10):
    """Download all images for a single chapter using threading."""
    safe_manga_title = sanitize_filename(manga_title)
    manga_dir = os.path.join(OUTPUT_DIR, safe_manga_title)
    chapter_dir = os.path.join(manga_dir, f"Episode {episode_number}")
    create_directory(chapter_dir)

    logger.info(f"Downloading {len(image_urls)} images for {manga_title} - Episode {episode_number} using {num_threads} threads")

    with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
        args_list = [(url, chapter_dir, i) for i, url in enumerate(image_urls, 1)]

        results = list(tqdm(executor.map(download_image, args_list), total=len(image_urls), desc=f"Downloading Episode {episode_number}"))

    successful_downloads = [res for res in results if res]
    logger.info(f"Successfully downloaded {len(successful_downloads)} / {len(image_urls)} images for Episode {episode_number}")

    return chapter_dir
