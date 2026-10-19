# utils/logger.py

import logging
from .config import LOG_FILE, LOG_FORMAT

def setup_logger():
    """Set up the application logger."""
    logging.basicConfig(
        level=logging.INFO,
        format=LOG_FORMAT,
        handlers=[
            logging.FileHandler(LOG_FILE),
            logging.StreamHandler()
        ]
    )
    return logging.getLogger(__name__)

logger = setup_logger()
