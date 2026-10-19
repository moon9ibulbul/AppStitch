# utils/config.py

# Base URLs
BASE_URL = "https://www.webtoons.com/{lang}"
SEARCH_URL = "https://www.webtoons.com/{lang}/search/originals?keyword={query}&page={page}"
LANGUAGE = "en"

# Output directory
OUTPUT_DIR = "output"

# Logging configuration
LOG_FILE = "webtoons_downloader.log"
LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"
