from PIL import ImageFile, Image as pil, UnidentifiedImageError
from natsort import natsorted
import numpy as np
import os
import subprocess
import time
import tempfile
import shutil
from OnnxSafety import BubbleDetector


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
            if imgFile.lower().endswith('.webp'):
                print("Processing .webp files...")
            imgPath = os.path.join(folder, imgFile)
            try:
                image = _open_image_with_webp_fallback(imgPath)
                if image is not None:
                    images.append(image)
            except UnidentifiedImageError as exc:
                print(f"Skipping unsupported or invalid image {imgPath}: {exc}")
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
    last = False
    loop_count = 0
    img_count = 0
    for imgFile in files:
        loop_count += 1
        if img_count < unit_limit and loop_count > offset:
            if imgFile.lower().endswith(('.png', '.webp', '.jpg', '.jpeg', '.jfif', '.bmp', '.tiff', '.tga')):
                if imgFile.lower().endswith('.webp'):
                    print("Processing .webp files...")
                imgPath = os.path.join(folder, imgFile)
                try:
                    image = _open_image_with_webp_fallback(imgPath)
                    if image is not None:
                        images.append(image)
                        img_count += 1
                except UnidentifiedImageError as exc:
                    print(f"Skipping unsupported or invalid image {imgPath}: {exc}")
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


def adjust_split_location(combined_pixels, split_height, split_offset, senstivity, ignorable_pixels, scan_step, unsafe_ranges=None):
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

        # Check if current row is in unsafe range (bubbles)
        is_unsafe_bubble = False
        if unsafe_ranges:
            for start, end in unsafe_ranges:
                if start <= split_row <= end:
                    is_unsafe_bubble = True
                    break

        if is_unsafe_bubble:
            if countdown:
                new_split_height -= scan_step
            else:
                new_split_height += scan_step
            adjust_in_progress = True
            # Bounds check logic duplicated below, but we continue to skip pixel check
            if new_split_height < 0.4 * split_height:
                new_split_height = split_height
                countdown = False
                adjust_in_progress = True
            continue

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

    # Init detector
    try:
        model_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "detector.onnx")
        detector = BubbleDetector(model_path)
    except Exception as e:
        print(f"Failed to init detector: {e}")
        detector = None

    # The spliting starts here (calls another function to decide where to slice)
    split_offset = 0
    while (split_offset + split_height) < max_height:
        unsafe_ranges = []
        if detector:
            # Probe Window Logic: Crop only a small region around the potential cut
            target_cut_y = split_offset + split_height
            probe_y_start = max(split_offset, target_cut_y - 1500)
            probe_y_end = min(max_height, target_cut_y + 500)

            # Ensure we have a valid crop height
            if probe_y_end > probe_y_start:
                try:
                    crop_img = combined_img.crop((0, probe_y_start, max_width, probe_y_end))
                    ranges_relative = detector.detect(crop_img)
                    # Convert relative coordinates to global
                    unsafe_ranges = [(s + probe_y_start, e + probe_y_start) for s, e in ranges_relative]
                except Exception as e:
                    print(f"Detection failed: {e}")

        new_split_height = adjust_split_location(combined_pixels, split_height, split_offset, senstivity,
                                                 ignorable_pixels, scan_step, unsafe_ranges)
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


def _index_to_letters(idx):
    letters = []
    num = idx
    while num > 0:
        num -= 1
        num, rem = divmod(num, 26)
        letters.append(chr(ord('a') + rem))
    return ''.join(reversed(letters)) or 'a'


def save_data(data, foldername, outputformat, offset=0, progress_func=None, filename_template=None, parent_name=None):
    """Saves the given images/date in the output folder."""
    st = time.time()
    new_folder = str(foldername)
    if not os.path.exists(new_folder):
        os.makedirs(new_folder)

    ext = outputformat.lstrip('.')
    template = filename_template or "{num}.{ext}"
    date_str = time.strftime("%Y%m%d")
    time_str = time.strftime("%H%M%S")
    resolved_parent = parent_name or os.path.basename(os.path.abspath(new_folder).rstrip(os.sep))

    def build_name(idx):
        replacements = {
            "num": f"{idx:02}",
            "ext": ext,
            "parent": resolved_parent,
            "time": time_str,
            "date": date_str,
            "char": _index_to_letters(idx),
        }
        name = template
        for key, value in replacements.items():
            name = name.replace(f"{{{key}}}", str(value))
        return name

    imageIndex = offset + 1
    for image in data:
        if progress_func is not None:
            progress_func(len(data))
        filename = build_name(imageIndex)
        image.save(os.path.join(new_folder, filename), quality=100)
        imageIndex += 1
    print(f"save_data: {time.time() - st}")
    return imageIndex - 1


def _open_image_with_webp_fallback(img_path):
    try:
        return pil.open(img_path)
    except UnidentifiedImageError:
        if img_path.lower().endswith(".webp"):
            converted_path = None
            try:
                converted_path = _convert_webp_to_png(img_path)
                fallback_image = pil.open(converted_path)
                fallback_image.load()
                return fallback_image
            except Exception as exc:
                print(f"Failed to convert WEBP {img_path} to PNG: {exc}")
            finally:
                if converted_path and os.path.exists(converted_path):
                    try:
                        os.remove(converted_path)
                    except OSError:
                        pass
        raise


def _convert_webp_to_png(img_path):
    tmp_png = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    tmp_png.close()

    ffmpeg_path = shutil.which("ffmpeg")
    if not ffmpeg_path:
        raise RuntimeError("ffmpeg tidak tersedia untuk konversi WEBP")

    result = subprocess.run(
        [ffmpeg_path, "-loglevel", "error", "-y", "-i", img_path, tmp_png.name],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        stderr = result.stderr.decode(errors="replace") if isinstance(result.stderr, (bytes, bytearray)) else str(result.stderr)
        raise RuntimeError(f"ffmpeg gagal mengonversi WEBP: {stderr}")

    return tmp_png.name


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
