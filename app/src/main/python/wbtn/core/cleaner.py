# core/cleaner.py

import shutil
from utils.logger import logger

def clean_chapter_images(chapter_dir):
    """Recursively delete the directory containing chapter images."""
    try:
        shutil.rmtree(chapter_dir)
        logger.info(f"Successfully cleaned up directory: {chapter_dir}")
    except OSError as e:
        logger.error(f"Error cleaning up directory {chapter_dir}: {e}")
