"""Start the simple upload-a-video Web demo."""
import argparse
import logging
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ROOT/"models/full-precision",
                        help="Directory containing either the ordinary or the quantized model pair")
    parser.add_argument("--face-models", type=Path, default=ROOT/"models/face")
    parser.add_argument("--output", type=Path, default=ROOT/"outputs")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8890)
    parser.add_argument("--max-side", type=int, default=960)
    parser.add_argument("--clip-seconds", type=float, default=10)
    parser.add_argument("--proxy", default=None, help="Optional cloud proxy; direct by default")
    args = parser.parse_args()
    if not 320 <= args.max_side <= 1920 or not 1 <= args.clip_seconds <= 30:
        parser.error("--max-side must be 320..1920; --clip-seconds must be 1..30")
    from inference import load_avatar
    from inference.models import select_model_files
    try:
        select_model_files(args.models)
    except FileNotFoundError as error:
        parser.error(str(error))
    for name in ("det_10g.onnx", "2d106det.onnx"):
        if not (args.face_models/name).is_file():
            parser.error(f"Missing {args.face_models/name}")
    from aiohttp import web
    from cloud import CloudConfig
    from server import create_app
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args.output.mkdir(parents=True, exist_ok=True)
    app = create_app(config=CloudConfig(proxy=args.proxy),
                     model_factory=lambda: load_avatar(models=args.models, cache_directory=args.output/"cache"),
                     face_models=args.face_models, output=args.output,
                     max_side=args.max_side, clip_seconds=args.clip_seconds)
    web.run_app(app, host=args.host, port=args.port, access_log=None, shutdown_timeout=30)


if __name__ == "__main__":
    main()

