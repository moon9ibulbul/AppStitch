import os
import requests
import numpy as np
import cv2
from PIL import Image

class BubbleDetector:
    def __init__(self, model_path):
        self.model_path = model_path
        self.net = None
        self._load_model()

    def _load_model(self):
        # We assume the model path is provided correctly.
        # If it doesn't exist, we can try to download it if we are allowed,
        # but for this app, we want the download to be explicit in the UI.
        # However, for backward compatibility or ease, if it's missing, we just fail gracefully.

        if not os.path.exists(self.model_path):
            print(f"Model not found at {self.model_path}")
            return

        try:
            self.net = cv2.dnn.readNetFromONNX(self.model_path)
            print("Model loaded successfully with OpenCV DNN.")
        except Exception as e:
            print(f"Failed to load model: {e}")

    def detect(self, pil_image):
        if self.net is None:
             return []

        # Preprocess
        original_w, original_h = pil_image.size
        input_w, input_h = 640, 640

        # PIL Resize is RGB
        img = pil_image.resize((input_w, input_h), Image.BILINEAR)
        img_data = np.array(img)

        # Handle channels
        if len(img_data.shape) == 2: # Grayscale
             img_data = np.stack((img_data,)*3, axis=-1)
        elif img_data.shape[2] == 4: # RGBA
             img_data = img_data[:, :, :3]

        blob = cv2.dnn.blobFromImage(img_data, 1/255.0, (input_w, input_h), swapRB=False, crop=False)

        # Inference
        try:
            self.net.setInput(blob)
            outputs = self.net.forward()
            output = outputs[0] # (5, 8400)
        except Exception as e:
            print(f"Inference failed: {e}")
            return []

        # Post-process
        output = output.transpose() # (8400, 5)

        score_threshold = 0.25
        nms_threshold = 0.45

        scores = output[:, 4]
        mask = scores > score_threshold
        filtered_output = output[mask]

        if len(filtered_output) == 0:
            return []

        filtered_scores = filtered_output[:, 4]
        filtered_boxes = filtered_output[:, 0:4] # cx, cy, w, h

        x_scale = original_w / input_w
        y_scale = original_h / input_h

        boxes_for_nms = []
        confidences = []

        for i in range(len(filtered_boxes)):
            cx, cy, w, h = filtered_boxes[i]

            w_scaled = w * x_scale
            h_scaled = h * y_scale

            x_tl = (cx - w/2) * x_scale
            y_tl = (cy - h/2) * y_scale

            boxes_for_nms.append([int(x_tl), int(y_tl), int(w_scaled), int(h_scaled)])
            confidences.append(float(filtered_scores[i]))

        indices = cv2.dnn.NMSBoxes(boxes_for_nms, confidences, score_threshold, nms_threshold)

        unsafe_ranges = []
        if len(indices) > 0:
            for i in indices.flatten():
                bx, by, bw, bh = boxes_for_nms[i]
                y_min = by
                y_max = by + bh
                y_min = max(0, y_min)
                y_max = min(original_h, y_max)
                unsafe_ranges.append((y_min, y_max))

        return unsafe_ranges

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
