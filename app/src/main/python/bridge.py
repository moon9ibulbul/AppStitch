import os, json, shutil
import SmartStitchCore as ssc
import main as stitch

class ProgressWriter:
    def __init__(self, path, offset=0):
        self.path = path
        self.processed = offset
        # Keep the denominator non-zero so the UI has something to read immediately
        self.total = max(offset + 1, 1)
        self._write()

    def _write(self, done=False):
        if not self.path:
            return
        try:
            with open(self.path, "w") as f:
                payload = {"processed": self.processed, "total": self.total}
                if done:
                    payload["done"] = True
                json.dump(payload, f)
        except Exception:
            pass

    def ensure_total(self, desired_total):
        if desired_total > self.total:
            self.total = desired_total
            self._write()

    def add_total(self, delta):
        self.ensure_total(self.total + delta)

    def step(self, inc=1):
        self.processed += inc
        if self.processed > self.total:
            self.total = self.processed
        self._write()

    def wrap_saver(self, total_images):
        self.add_total(total_images)

        def cb(*_):
            self.step()

        return cb

    def finish(self, mark_done=True):
        self.processed = max(self.processed, self.total)
        self._write(done=mark_done)


def _resolve_output_folder(input_folder, output_folder):
    input_abs = os.path.abspath(input_folder)
    if output_folder:
        return os.path.abspath(output_folder)

    parent_dir = os.path.dirname(input_abs)
    folder_name = os.path.basename(input_abs.rstrip(os.sep)) or os.path.basename(parent_dir)
    return os.path.join(parent_dir, f"{folder_name} [Stitched]")

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
        filename_template=None,
        zip_output=False,
        pdf_output=False,
        progress_path=None,
        progress_offset=0,
        mark_done=True,
        split_mode=0):

    if output_files_type == ".webp":
        output_files_type = ".bmp"
        zip_output = False
        pdf_output = False

    resolved_output_folder = _resolve_output_folder(input_folder, output_folder)
    progress_file = progress_path or os.path.join(resolved_output_folder, "progress.json")

    writer = ProgressWriter(progress_file, progress_offset)

    # REMOVED GLOBAL MONKEY-PATCHING OF ssc.save_data
    # We now pass the writer to main.run_stitch_process which handles it.

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
        output_folder=resolved_output_folder,
        filename_template=filename_template,
        progress_writer=writer,
        split_mode=split_mode
    )

    writer.finish(mark_done)

    # Clean progress file from output folder before packaging
    prog_in_out = os.path.join(resolved_output_folder, "progress.json")
    if os.path.exists(prog_in_out):
        try:
            os.remove(prog_in_out)
        except Exception:
            pass

    if zip_output:
        return pack_archive(resolved_output_folder, "ZIP")

    if pdf_output:
        return pack_archive(resolved_output_folder, "PDF")

    return resolved_output_folder

def pack_archive(source_path, fmt):
    source_path = os.path.abspath(source_path)
    if fmt == "ZIP":
        zip_path = shutil.make_archive(
            source_path,
            "zip",
            root_dir=os.path.dirname(source_path),
            base_dir=os.path.basename(source_path)
        )
        shutil.rmtree(source_path, ignore_errors=True)
        return zip_path
    elif fmt == "PDF":
        image_paths = [
            os.path.join(source_path, name)
            for name in sorted(os.listdir(source_path))
            if name.lower().endswith((
                ".png", ".jpg", ".jpeg", ".jfif", ".webp", ".bmp", ".tiff", ".tif", ".tga"
            ))
        ]
        if not image_paths:
            print("No images found for PDF conversion.")
            return source_path

        images = []
        try:
            for path in image_paths:
                image = None
                try:
                    if path.lower().endswith(".webp"):
                         print("Processing .webp files...")
                    image = ssc._open_image_with_webp_fallback(path)
                    converted = image.convert("RGB")
                    images.append(converted)
                except Exception as exc:
                    print(f"Skipping invalid image {path}: {exc}")
                finally:
                    try:
                        if image: image.close()
                    except Exception:
                        pass

            if not images:
                 return source_path

            first, *rest = images
            pdf_path = f"{source_path}.pdf"
            first.save(pdf_path, save_all=True, append_images=rest)
        finally:
            for img in images:
                try: img.close()
                except: pass

        shutil.rmtree(source_path, ignore_errors=True)
        return pdf_path

    return source_path
