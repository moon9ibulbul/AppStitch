from PIL import ImageFile, Image as pil
from PIL import UnidentifiedImageError
import imageio.v2 as imageio
from natsort import natsorted
import numpy as np
import os
import subprocess
import time


ImageFile.LOAD_TRUNCATED_IMAGES = True


def get_folder_paths(batch_mode_enabled, given_input_folder, given_output_folder):
    """Gets paths of all input and output folders."""
    st = time.time()
    folder_paths = []
    given_input_folder = os.path.abspath(given_input_folder)
    given_output_folder = os.path.abspath(given_output_folder)
    if not batch_mode_enabled:
        folder_paths.append((given_input_folder, given_output_folder))
    else:
        # Gets Absolute paths to the folders within the given path
        for fileName in os.listdir(given_input_folder):
            filePath = os.path.join(given_input_folder, fileName)
            if os.path.isdir(filePath):
                folder_paths.append((filePath, os.path.join(given_output_folder, fileName + " [Stitched]")))
    print(f"get_folder_paths: {time.time() - st}")
    return folder_paths


def load_images(foldername):
    """Loads all image files in a given folder into a list of pillow image objects."""
    st = time.time()
    images = []
    if foldername == "":
        return images
    folder = os.path.abspath(str(foldername))
    files = natsorted(os.listdir(folder))
    if len(files) == 0:
        return images
    for imgFile in files:
        if imgFile.lower().endswith(('.png', '.webp', '.jpg', '.jpeg', '.jfif', '.bmp', '.tiff', '.tga')):
            imgPath = os.path.join(folder, imgFile)
            normalized_path = _normalize_input_image(imgPath)
            if normalized_path is None or not os.path.isfile(normalized_path):
                continue
            images.append(_open_image_safe(normalized_path))
    print(f"load_images: {time.time() - st}")
    return images


def load_unit_images(foldername, first_image=None, offset=0, unit_limit=20):
    """Loads all image files in a given folder into a list of pillow image objects."""
    st = time.time()
    images = []
    if first_image is not None:
        images.append(first_image)
    if foldername == "":
        return images
    folder = os.path.abspath(str(foldername))
    files = natsorted(os.listdir(folder))
    if len(files) == 0:
        return images
    loop_count = 0
    img_count = 0
    for imgFile in files:
        loop_count += 1
        if img_count < unit_limit and loop_count > offset:
            if imgFile.lower().endswith(('.png', '.webp', '.jpg', '.jpeg', '.jfif', '.bmp', '.tiff', '.tga')):
                imgPath = os.path.join(folder, imgFile)
                normalized_path = _normalize_input_image(imgPath)
                if normalized_path is None or not os.path.isfile(normalized_path):
                    continue
                images.append(_open_image_safe(normalized_path))
                img_count += 1
            last = True
        else:
            last = False
    if len(images) >= unit_limit and not last:
        offset += unit_limit
    else:
        offset = None
    print(f"load_unit_images: {time.time() - st}, last: {last}, offset: {offset}")
    return images, offset


def resize_images(images, width_enforce_type, custom_width=720):
    """Resizes the images according to what enforcement mode you have."""
    st = time.time()
    if width_enforce_type == 0:
        return images
    else:
        resized_images = []
        new_image_width = 0
        if width_enforce_type == 1:
            widths, heights = zip(*(image.size for image in images))
            new_image_width = min(widths)
        elif width_enforce_type == 2:
            new_image_width = int(custom_width)
        for image in images:
            if image.size[0] == new_image_width:
                resized_images.append(image)
            else:
                ratio = float(image.size[1] / image.size[0])
                new_image_height = int(ratio * new_image_width)
                if new_image_height == 0:
                    continue
                new_image = image.resize((new_image_width, new_image_height), pil.Resampling.LANCZOS)
                resized_images.append(new_image)
                image.close()
        print(f"resize_images: {time.time() - st}")
        return resized_images


def combine_images(images):
    """All this does is combine all the files into a single image in the memory."""
    st = time.time()
    widths, heights = zip(*(image.size for image in images))
    new_image_width = max(widths)
    new_image_height = sum(heights)
    new_image = pil.new('RGB', (new_image_width, new_image_height))
    combine_offset = 0
    for image in images:
        new_image.paste(image, (0, combine_offset))
        combine_offset += image.size[1]
        image.close()
    print(f"combine_images: {time.time() - st}")
    return new_image


def adjust_split_location(combined_pixels, split_height, split_offset, senstivity, ignorable_pixels, scan_step):
    """Where the smart magic happens, compares pixels of each row, to decide if it's okay to cut there."""
    st = time.time()
    threshold = int(255 * (1 - (senstivity / 100)))
    new_split_height = split_height
    last_row = len(combined_pixels) - 1
    adjust_in_progress = True
    countdown = True
    while adjust_in_progress:
        adjust_in_progress = False
        split_row = split_offset + new_split_height
        if split_row > last_row:
            break
        pixel_row = combined_pixels[split_row]
        prev_pixel = int(pixel_row[ignorable_pixels])
        for x in range((ignorable_pixels + 1), len(pixel_row) - (ignorable_pixels)):
            current_pixel = int(pixel_row[x])
            pixel_value_diff = current_pixel - prev_pixel
            if pixel_value_diff < -threshold or pixel_value_diff > threshold:
                if countdown:
                    new_split_height -= scan_step
                else:
                    new_split_height += scan_step
                adjust_in_progress = True
                break
            current_pixel = prev_pixel
        if new_split_height < 0.4 * split_height:
            new_split_height = split_height
            countdown = False
            adjust_in_progress = True
    print(f"adjust_split_location: {time.time() - st}")
    return new_split_height


def split_image(combined_img, split_height, senstivity, ignorable_pixels, scan_step):
    """Splits the gaint combined img into small images passed on desired height."""
    st = time.time()
    split_height = int(split_height)
    senstivity = int(senstivity)
    ignorable_pixels = int(ignorable_pixels)
    scan_step = int(scan_step)
    max_width = combined_img.size[0]
    max_height = combined_img.size[1]
    combined_pixels = np.array(combined_img.convert('L'))
    images = []
    # The spliting starts here (calls another function to decide where to slice)
    split_offset = 0
    while (split_offset + split_height) < max_height:
        new_split_height = adjust_split_location(combined_pixels, split_height, split_offset, senstivity,
                                                 ignorable_pixels, scan_step)
        split_image = pil.new('RGB', (max_width, new_split_height))
        split_image.paste(combined_img, (0, -split_offset))
        split_offset += new_split_height
        images.append(split_image)
    # Final image (What ever is remaining in the combined img, will be smaller than the rest for sure)
    remaining_rows = max_height - split_offset
    if remaining_rows > 0:
        split_image = pil.new('RGB', (max_width, max_height - split_offset))
        split_image.paste(combined_img, (0, -split_offset))
        images.append(split_image)
    combined_img.close()
    print(f"split_image: {time.time() - st}")
    return images


def save_data(data, foldername, outputformat, offset=0, progress_func=None):
    """Saves the given images/date in the output folder."""
    st = time.time()
    new_folder = str(foldername)
    if not os.path.exists(new_folder):
        os.makedirs(new_folder)
    imageIndex = offset + 1
    extension = outputformat.lower()
    target_format, save_kwargs = _resolve_output_format(extension)
    for image in data:
        if progress_func is not None:
            progress_func(len(data))
        processed_image = _prepare_image_for_save(image, extension)
        target_path = os.path.join(new_folder, f"{imageIndex:02}{outputformat}")
        processed_image.save(target_path, format=target_format, **save_kwargs)
        processed_image.close()
        if processed_image is not image:
            image.close()
        imageIndex += 1
    print(f"save_data: {time.time() - st}")
    return imageIndex - 1


def _open_image_safe(path):
    try:
        return pil.open(path).convert("RGBA")
    except UnidentifiedImageError as exc:
        raise RuntimeError(f"Tidak dapat membaca file gambar: {os.path.basename(path)}") from exc


def _resolve_output_format(extension):
    mapping = {
        ".png": ("PNG", {"optimize": True}),
        ".jpg": ("JPEG", {"quality": 100}),
        ".jpeg": ("JPEG", {"quality": 100}),
        ".bmp": ("BMP", {}),
        ".tiff": ("TIFF", {}),
        ".tif": ("TIFF", {}),
        ".tga": ("TGA", {}),
    }
    if extension not in mapping:
        raise ValueError(f"Unknown file extension: {extension}")
    return mapping[extension]


def _prepare_image_for_save(image, extension):
    if extension in (".jpg", ".jpeg"):
        return image.convert("RGB")
    if image.mode == "P":
        return image.convert("RGBA")
    return image


def _normalize_input_image(path):
    extension = os.path.splitext(path)[1].lower()
    if not os.path.isfile(path):
        return None
    if _is_webp_file(path):
        if extension in (".jpg", ".jpeg"):
            new_path = os.path.splitext(path)[0] + ".webp"
            if not os.path.exists(new_path):
                os.rename(path, new_path)
            path = new_path
        if path.lower().endswith(".webp"):
            return _convert_webp_to_jpg(path)
    return path


def _convert_webp_to_jpg(path):
    jpg_path = os.path.splitext(path)[0] + ".jpg"
    if os.path.exists(jpg_path):
        return jpg_path
    try:
        with pil.open(path) as image:
            image.convert("RGB").save(jpg_path, format="JPEG", quality=100)
    except UnidentifiedImageError:
        jpg_path = _convert_webp_with_imageio(path, jpg_path)
    return jpg_path


def _is_webp_file(path):
    try:
        with open(path, "rb") as f:
            header = f.read(12)
        return header[:4] == b"RIFF" and header[8:] == b"WEBP"
    except OSError:
        return False


def _convert_webp_with_imageio(path, jpg_path):
    try:
        image = imageio.imread(path)
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"Tidak dapat membaca file gambar: {os.path.basename(path)}") from exc

    try:
        pil.fromarray(image).convert("RGB").save(jpg_path, format="JPEG", quality=100)
        return jpg_path
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"Tidak dapat membaca file gambar: {os.path.basename(path)}") from exc


def call_external_func(cmd, display_output, processed_path):
    if not os.path.exists(processed_path) and '[Processed]' in cmd:
        os.makedirs(processed_path)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace',
                            universal_newlines=True, shell=True)
    display_output("Subprocess started!\n")
    for line in proc.stdout:
        display_output(line)
    # for line in proc.stderr:
    #   display_output(line)
    display_output("\nSubprocess finished successfully!\n")
    proc.stdout.close()
    return_code = proc.wait()
    if return_code:
        raise subprocess.CalledProcessError(return_code, cmd)
