"""Run NanoAvatar Web with local, separately downloaded PyTorch weights."""
import argparse
import logging
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avatar", type=Path, required=True, help="Directory containing avatar.json")
    parser.add_argument("--models", type=Path, default=Path(__file__).resolve().parents[1] / "models/full-precision",
                        help="Directory containing hubert_fp16.pt + lip_fp32.pt, or hubert_w8a16.pt + lip_mixed_int8.pt")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--proxy", default=None, help="Cloud HTTP proxy; direct by default")
    args = parser.parse_args()
    if not (args.avatar / "avatar.json").is_file():
        parser.error("--avatar must point to a complete person package containing avatar.json")
    from inference.models import select_model_files
    try:
        select_model_files(args.models)
    except FileNotFoundError as error:
        parser.error(str(error))
    from inference import load_avatar
    from aiohttp import web
    from cloud import CloudConfig
    from server import create_app
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    app = create_app(config=CloudConfig(proxy=args.proxy),
                     model_factory=lambda: load_avatar(args.avatar, models=args.models))
    web.run_app(app, host=args.host, port=args.port, access_log=None, shutdown_timeout=30)


if __name__ == "__main__":
    main()
