# core/converter.py

import os
import zipfile
from PIL import Image
from utils.logger import logger

def convert_to_pdf(chapter_dir, output_path):
    """Convert a directory of images to a PDF file."""
    images = []
    image_files = sorted([f for f in os.listdir(chapter_dir) if f.endswith(".jpg")])

    if not image_files:
        logger.warning(f"No images found in {chapter_dir} to convert to PDF.")
        return

    for image_file in image_files:
        image_path = os.path.join(chapter_dir, image_file)
        try:
            img = Image.open(image_path)
            img = img.convert("RGB")
            images.append(img)
        except IOError as e:
            logger.error(f"Error opening image {image_path}: {e}")
            return

    if images:
        images[0].save(output_path, save_all=True, append_images=images[1:])
        logger.info(f"Successfully created PDF: {output_path}")

def convert_to_cbz(chapter_dir, output_path):
    """Convert a directory of images to a CBZ file."""
    image_files = sorted([f for f in os.listdir(chapter_dir) if f.endswith(".jpg")])

    if not image_files:
        logger.warning(f"No images found in {chapter_dir} to convert to CBZ.")
        return

    with zipfile.ZipFile(output_path, 'w') as cbz:
        for image_file in image_files:
            image_path = os.path.join(chapter_dir, image_file)
            cbz.write(image_path, arcname=image_file)

    logger.info(f"Successfully created CBZ: {output_path}")
