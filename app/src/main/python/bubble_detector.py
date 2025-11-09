import functools
import io
import os
import threading
from dataclasses import dataclass
from typing import List, Sequence, Tuple

import numpy as np
from PIL import Image

try:
    from huggingface_hub import hf_hub_download
except Exception:  # pragma: no cover - Chaquopy may defer installation
    hf_hub_download = None  # type: ignore

try:
    import onnxruntime as ort
except Exception:  # pragma: no cover
    ort = None  # type: ignore


_REPO_ID = "ogkalu/comic-text-and-bubble-detector"
_DEFAULT_MODEL_FILES = (
    "model_float16.onnx",
    "model.onnx",
)


@dataclass
class Detection:
    x1: float
    y1: float
    x2: float
    y2: float
    score: float
    label: int


class ComicBubbleDetector:
    """Lightweight YOLO detector wrapper for speech-bubble avoidance."""

    def __init__(
        self,
        confidence: float = 0.25,
        iou_threshold: float = 0.45,
        device: str = "cpu",
        cache_dir: str | None = None,
    ) -> None:
        if hf_hub_download is None or ort is None:
            raise RuntimeError(
                "onnxruntime and huggingface-hub must be installed for bubble detection"
            )

        self.confidence = confidence
        self.iou_threshold = iou_threshold
        self.device = device
        self._session = None
        self._session_lock = threading.Lock()
        self.model_path = self._resolve_model(cache_dir)

    def _resolve_model(self, cache_dir: str | None) -> str:
        if cache_dir is None:
            cache_dir = os.path.join(os.path.expanduser("~"), ".cache", "appstitch")
        os.makedirs(cache_dir, exist_ok=True)

        for filename in _DEFAULT_MODEL_FILES:
            local_path = os.path.join(cache_dir, filename)
            if os.path.exists(local_path):
                return local_path

        last_error: Exception | None = None
        for filename in _DEFAULT_MODEL_FILES:
            try:
                local_path = hf_hub_download(
                    _REPO_ID,
                    filename=filename,
                    repo_type="model",
                    cache_dir=cache_dir,
                )
                return local_path
            except Exception as exc:  # pragma: no cover - network failures handled gracefully
                last_error = exc
        raise RuntimeError(
            "Unable to download detector weights from Hugging Face" +
            (f": {last_error}" if last_error else "")
        )

    def _ensure_session(self) -> "ort.InferenceSession":
        if self._session is None:
            with self._session_lock:
                if self._session is None:
                    providers = None
                    if self.device != "cpu":
                        providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
                    sess_opts = ort.SessionOptions()
                    sess_opts.inter_op_num_threads = 1
                    sess_opts.intra_op_num_threads = max(1, os.cpu_count() or 1)
                    self._session = ort.InferenceSession(
                        self.model_path,
                        sess_options=sess_opts,
                        providers=providers,
                    )
        return self._session

    def _letterbox(self, image: np.ndarray, new_shape: Tuple[int, int] = (640, 640)) -> Tuple[np.ndarray, float, Tuple[int, int]]:
        shape = image.shape[:2]
        ratio = min(new_shape[0] / shape[0], new_shape[1] / shape[1])
        new_unpad = (int(round(shape[1] * ratio)), int(round(shape[0] * ratio)))
        dw = new_shape[1] - new_unpad[0]
        dh = new_shape[0] - new_unpad[1]
        dw /= 2
        dh /= 2

        if shape[::-1] != new_unpad:
            image = Image.fromarray(image)
            image = image.resize(new_unpad, Image.Resampling.BILINEAR)
            image = np.asarray(image)

        top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
        left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
        color = (114, 114, 114)
        image = np.pad(image, ((top, bottom), (left, right), (0, 0)), constant_values=((color[0], color[0]), (color[1], color[1]), (color[2], color[2])))
        return image, ratio, (dw, dh)

    def _preprocess(self, pil_image: Image.Image) -> Tuple[np.ndarray, float, Tuple[int, int]]:
        image = np.asarray(pil_image.convert("RGB"))
        image, ratio, pad = self._letterbox(image)
        image = image.transpose((2, 0, 1))
        image = np.ascontiguousarray(image, dtype=np.float32)
        image /= 255.0
        return image[None, ...], ratio, pad

    def _postprocess(
        self,
        preds: np.ndarray,
        ratio: float,
        pad: Tuple[float, float],
        original_shape: Tuple[int, int],
    ) -> List[Detection]:
        preds = preds[0]
        boxes = preds[:, :4]
        scores = preds[:, 4]
        if preds.shape[1] > 5:
            class_scores = preds[:, 5:]
            class_ids = np.argmax(class_scores, axis=1)
            scores = scores * class_scores.max(axis=1)
        else:
            class_ids = np.zeros_like(scores, dtype=np.int64)

        mask = scores >= self.confidence
        boxes = boxes[mask]
        scores = scores[mask]
        class_ids = class_ids[mask]
        if boxes.size == 0:
            return []

        # Convert from center xywh to xyxy if necessary
        if np.max(boxes[:, 0]) <= 1 and np.max(boxes[:, 2]) <= 1:
            boxes = boxes * np.array([640, 640, 640, 640], dtype=np.float32)
        if np.any(boxes[:, 2] <= 1) or np.any(boxes[:, 3] <= 1):
            # assume xywh
            boxes_xyxy = np.zeros_like(boxes)
            boxes_xyxy[:, 0] = boxes[:, 0] - boxes[:, 2] / 2
            boxes_xyxy[:, 1] = boxes[:, 1] - boxes[:, 3] / 2
            boxes_xyxy[:, 2] = boxes[:, 0] + boxes[:, 2] / 2
            boxes_xyxy[:, 3] = boxes[:, 1] + boxes[:, 3] / 2
            boxes = boxes_xyxy

        # Undo letterbox
        gain = ratio
        pad_x, pad_y = pad
        boxes[:, [0, 2]] -= pad_x
        boxes[:, [1, 3]] -= pad_y
        boxes /= gain

        height, width = original_shape
        boxes[:, 0] = np.clip(boxes[:, 0], 0, width - 1)
        boxes[:, 1] = np.clip(boxes[:, 1], 0, height - 1)
        boxes[:, 2] = np.clip(boxes[:, 2], 0, width - 1)
        boxes[:, 3] = np.clip(boxes[:, 3], 0, height - 1)

        keep = self._nms(boxes, scores)
        detections = [
            Detection(
                x1=float(boxes[i, 0]),
                y1=float(boxes[i, 1]),
                x2=float(boxes[i, 2]),
                y2=float(boxes[i, 3]),
                score=float(scores[i]),
                label=int(class_ids[i]),
            )
            for i in keep
        ]
        return detections

    def _nms(self, boxes: np.ndarray, scores: np.ndarray) -> List[int]:
        if len(boxes) == 0:
            return []
        x1 = boxes[:, 0]
        y1 = boxes[:, 1]
        x2 = boxes[:, 2]
        y2 = boxes[:, 3]
        areas = (x2 - x1 + 1) * (y2 - y1 + 1)
        order = scores.argsort()[::-1]
        keep = []
        while order.size > 0:
            i = order[0]
            keep.append(int(i))
            if order.size == 1:
                break
            xx1 = np.maximum(x1[i], x1[order[1:]])
            yy1 = np.maximum(y1[i], y1[order[1:]])
            xx2 = np.minimum(x2[i], x2[order[1:]])
            yy2 = np.minimum(y2[i], y2[order[1:]])

            w = np.maximum(0.0, xx2 - xx1)
            h = np.maximum(0.0, yy2 - yy1)
            inter = w * h
            iou = inter / (areas[i] + areas[order[1:]] - inter + 1e-6)
            inds = np.where(iou <= self.iou_threshold)[0]
            order = order[inds + 1]
        return keep

    def detect(self, pil_image: Image.Image) -> List[Detection]:
        session = self._ensure_session()
        tensor, ratio, pad = self._preprocess(pil_image)
        input_name = session.get_inputs()[0].name
        ort_inputs = {input_name: tensor}
        ort_outputs = session.run(None, ort_inputs)
        preds = ort_outputs[0]
        if preds.ndim == 3:
            preds = np.transpose(preds, (0, 2, 1))
            preds = preds.reshape(preds.shape[0], -1, preds.shape[-1])
        detections = self._postprocess(preds, ratio, pad, pil_image.size[::-1])
        return detections

    def protected_rows(
        self,
        pil_image: Image.Image,
        padding: int = 8,
    ) -> Sequence[Tuple[int, int]]:
        detections = self.detect(pil_image)
        rows: List[Tuple[int, int]] = []
        for det in detections:
            top = max(0, int(det.y1) - padding)
            bottom = min(pil_image.size[1], int(det.y2) + padding)
            rows.append((top, bottom))
        rows.sort()
        merged: List[Tuple[int, int]] = []
        for start, end in rows:
            if not merged or start > merged[-1][1]:
                merged.append((start, end))
            else:
                merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        return merged
