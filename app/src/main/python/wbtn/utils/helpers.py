# utils/helpers.py

import os
import re
from .logger import logger

def create_directory(path):
    """Create a directory if it does not exist."""
    if not os.path.exists(path):
        try:
            os.makedirs(path)
            logger.info(f"Created directory: {path}")
        except OSError as e:
            logger.error(f"Error creating directory {path}: {e}")
            raise

def parse_views(view_str):
    """Parse view counts like '129M' or '598K' into integers."""
    view_str = view_str.upper()
    if 'M' in view_str:
        return int(float(view_str.replace('M', '')) * 1_000_000)
    elif 'K' in view_str:
        return int(float(view_str.replace('K', '')) * 1_000)
    else:
        return int(view_str)

def sanitize_filename(name):
    """Remove invalid characters from a string to make it a valid filename."""
    return re.sub(r'[\\/*?:"<>|]',"", name)
