from PIL import ImageFile, Image as pil
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
            image = pil.open(imgPath)
            images.append(image)
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
                image = pil.open(imgPath)
                images.append(image)
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
    last_row = len(combined_pixels) - 1
    target_row = min(split_offset + split_height, last_row)

    # Define a search window around the desired split location to look for calmer rows.
    search_window = max(int(split_height * 0.15), scan_step * 3)
    search_start = max(split_offset, target_row - search_window)
    search_end = min(last_row - 1, target_row + search_window)

    if search_start >= search_end:
        print(f"adjust_split_location: {time.time() - st}")
        return split_height

    sample_stride = max(1, scan_step // 2)
    edge_limit = max(12, threshold + 8)
    max_dark_ratio = min(0.45, 0.2 + (senstivity / 500))

    def row_metrics(row_pixels):
        trimmed = row_pixels[ignorable_pixels:len(row_pixels) - ignorable_pixels]
        if trimmed.size <= 1:
            return 0.0, 0.0
        if sample_stride > 1:
            trimmed = trimmed[::sample_stride]
        diffs = np.abs(np.diff(trimmed.astype(np.int16)))
        avg_diff = float(diffs.mean()) if diffs.size > 0 else 0.0
        dark_ratio = float(np.count_nonzero(trimmed < 180)) / float(trimmed.size)
        return avg_diff, dark_ratio

    best_row = target_row
    best_score = float('inf')
    for row_idx in range(search_start, search_end + 1):
        avg_diff, dark_ratio = row_metrics(combined_pixels[row_idx])
        if dark_ratio > max_dark_ratio and avg_diff > edge_limit:
            continue
        distance_penalty = abs(row_idx - target_row) * 0.2
        combined_score = avg_diff + distance_penalty
        if combined_score < best_score:
            best_score = combined_score
            best_row = row_idx

    if best_score == float('inf'):
        print(f"adjust_split_location: {time.time() - st}")
        return split_height

    adjusted_height = best_row - split_offset
    if adjusted_height <= 0:
        adjusted_height = split_height
    else:
        minimum_height = max(1, int(split_height * 0.4))
        if adjusted_height < minimum_height:
            adjusted_height = minimum_height

    print(f"adjust_split_location: {time.time() - st}")
    return int(adjusted_height)


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
    for image in data:
        if progress_func is not None:
            progress_func(len(data))
        image.save(new_folder + '/' + str(f'{imageIndex:02}') + outputformat, quality=100)
        imageIndex += 1
    print(f"save_data: {time.time() - st}")
    return imageIndex - 1


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
