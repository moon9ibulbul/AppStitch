import os
import requests
import io
import time
from com.astral.stitchapp import BubbleDetector as KotlinBubbleDetector

class BubbleDetector:
    def __init__(self, model_path):
        self.model_path = model_path
        self.detector = None
        self._load_model()

    def _load_model(self):
        if not os.path.exists(self.model_path):
            print(f"Model not found at {self.model_path}")
            return

        try:
            self.detector = KotlinBubbleDetector(self.model_path)
            print("Kotlin BubbleDetector loaded successfully.")
        except Exception as e:
            print(f"Failed to load Kotlin detector: {e}")

    def detect(self, pil_image):
        if self.detector is None:
             print("Detector is None, returning empty.")
             return []

        # Convert PIL image to bytes (PNG)
        try:
            img_byte_arr = io.BytesIO()
            pil_image.save(img_byte_arr, format='PNG')
            img_bytes = img_byte_arr.getvalue()

            # Debug info
            w, h = pil_image.size
            print(f"ONNX Detect Start: Image {w}x{h}, sending {len(img_bytes)} bytes to Kotlin")

            st = time.time()
            # Returns Array<IntArray> which Chaquopy converts to list of lists/arrays
            ranges = self.detector.detect(img_bytes)

            # ranges is likely a Java Array.
            # Convert to python list of tuples
            result = []
            if ranges:
                for r in ranges:
                    # r is IntArray
                    result.append((r[0], r[1]))

            print(f"Kotlin detect: found {len(result)} ranges in {time.time()-st:.3f}s")
            return result
        except Exception as e:
            print(f"Inference failed in Python wrapper: {e}")
            import traceback
            traceback.print_exc()
            return []

def download_model(save_path):
    print(f"Downloading model to {save_path}...")
    url = "https://huggingface.co/bulbulmoon/lama/resolve/main/detector.onnx"
    try:
        # Ensure directory exists
        model_dir = os.path.dirname(save_path)
        if model_dir and not os.path.exists(model_dir):
            os.makedirs(model_dir)

        response = requests.get(url, stream=True)
        response.raise_for_status()
        with open(save_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
        print("Model downloaded successfully.")
        return True
    except Exception as e:
        print(f"Failed to download model: {e}")
        return False
