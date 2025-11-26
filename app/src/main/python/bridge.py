import os, json, shutil
import SmartStitchCore as ssc
import main as stitch
from PIL import Image

PROGRESS_FILE = None


def _resolve_output_folder(input_folder, output_folder):
    input_abs = os.path.abspath(input_folder)
    if output_folder:
        return os.path.abspath(output_folder)

    parent_dir = os.path.dirname(input_abs)
    folder_name = os.path.basename(input_abs.rstrip(os.sep)) or os.path.basename(parent_dir)
    return os.path.join(parent_dir, f"{folder_name} [Stitched]")

def _progress_cb_factory(total_images):
    count = {"n": 0}
    def cb(_n=None):
        count["n"] += 1
        if PROGRESS_FILE:
            try:
                with open(PROGRESS_FILE, "w") as f:
                    json.dump({"processed": count["n"], "total": total_images}, f)
            except Exception:
                pass
    return cb

def run(input_folder,
        split_height=5000,
        output_files_type=".png",
        batch_mode=False,
        width_enforce_type=0,
        custom_width=720,
        senstivity=90,
        ignorable_pixels=0,
        scan_line_step=5,
        low_ram=False,
        unit_images=20,
        output_folder=None,
        zip_output=False,
        pdf_output=False):
    global PROGRESS_FILE
    resolved_output_folder = _resolve_output_folder(input_folder, output_folder)
    PROGRESS_FILE = os.path.join(resolved_output_folder, "progress.json")

    _orig_save = ssc.save_data

    def save_data_with_progress(data, foldername, outputformat, offset=0, progress_func=None):
        total = len(data)
        cb = _progress_cb_factory(total)
        return _orig_save(data, foldername, outputformat, offset=offset, progress_func=cb)

    ssc.save_data = save_data_with_progress

    stitch.run_stitch_process(
        input_folder=input_folder,
        split_height=split_height,
        output_files_type=output_files_type,
        batch_mode=batch_mode,
        width_enforce_type=width_enforce_type,
        custom_width=custom_width,
        senstivity=senstivity,
        ignorable_pixels=ignorable_pixels,
        scan_line_step=scan_line_step,
        low_ram=low_ram,
        unit_images=unit_images,
        output_folder=resolved_output_folder
    )

    ssc.save_data = _orig_save

    if PROGRESS_FILE:
        with open(PROGRESS_FILE, "w") as f:
            json.dump({"done": True}, f)
        try:
            os.remove(PROGRESS_FILE)
        except OSError:
            pass
        PROGRESS_FILE = None

    if zip_output:
        zip_path = shutil.make_archive(
            resolved_output_folder,
            "zip",
            root_dir=os.path.dirname(resolved_output_folder),
            base_dir=os.path.basename(resolved_output_folder)
        )
        shutil.rmtree(resolved_output_folder, ignore_errors=True)
        return zip_path

    if pdf_output:
        image_paths = [
            os.path.join(resolved_output_folder, name)
            for name in sorted(os.listdir(resolved_output_folder))
            if name.lower().endswith((
                ".png", ".jpg", ".jpeg", ".jfif", ".webp", ".bmp", ".tiff", ".tif", ".tga"
            ))
        ]
        if not image_paths:
            raise RuntimeError("Tidak ada gambar untuk dikonversi ke PDF")

        images = []
        try:
            for path in image_paths:
                with Image.open(path) as img:
                    images.append(img.convert("RGB"))

            first, *rest = images
            pdf_path = f"{resolved_output_folder}.pdf"
            first.save(pdf_path, save_all=True, append_images=rest)
        finally:
            for img in images:
                img.close()

        shutil.rmtree(resolved_output_folder, ignore_errors=True)
        return pdf_path

    return resolved_output_folder
