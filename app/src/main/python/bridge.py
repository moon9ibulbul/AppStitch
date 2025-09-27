import os, json
import SmartStitchCore as ssc
import main as stitch

PROGRESS_FILE = None

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
        output_folder=None):
    global PROGRESS_FILE
    if output_folder is None:
        PROGRESS_FILE = os.path.join(os.path.abspath(input_folder) + " [Stitched]", "progress.json")
    else:
        PROGRESS_FILE = os.path.join(os.path.abspath(output_folder), "progress.json")

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
        output_folder=output_folder
    )

    ssc.save_data = _orig_save

    if PROGRESS_FILE:
        with open(PROGRESS_FILE, "w") as f:
            json.dump({"done": True}, f)
