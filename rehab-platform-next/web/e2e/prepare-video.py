"""Build a Chromium fake camera fixture from the official MediaPipe pose image."""

from hashlib import sha256
from io import BytesIO
from pathlib import Path
from urllib.request import urlopen

from PIL import Image, ImageOps


SOURCE = 'https://storage.googleapis.com/mediapipe-assets/pose.jpg'
SOURCE_SHA256 = 'c8a830ed683c0276d713dd5aeda28f415f10cd6291972084a40d0d8b934ed62b'
WIDTH, HEIGHT, FPS = 640, 480, 15
FIXTURE = Path(__file__).resolve().parent / '.fixtures' / 'pose.y4m'


def main():
    with urlopen(SOURCE, timeout=30) as response:
        jpeg = response.read(128 * 1024)
    if sha256(jpeg).hexdigest() != SOURCE_SHA256:
        raise RuntimeError('The MediaPipe example differs from the documented fixture')

    portrait = Image.open(BytesIO(jpeg)).convert('RGB')
    frame = ImageOps.pad(portrait, (WIDTH, HEIGHT), color=(230, 230, 230))
    luma, chroma_u, chroma_v = frame.convert('YCbCr').split()
    chroma_size = (WIDTH // 2, HEIGHT // 2)
    pixels = (luma.tobytes() + chroma_u.resize(chroma_size).tobytes()
              + chroma_v.resize(chroma_size).tobytes())

    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    temporary = FIXTURE.with_suffix('.tmp')
    with temporary.open('wb') as file:
        file.write(f'YUV4MPEG2 W{WIDTH} H{HEIGHT} F{FPS}:1 Ip A1:1 C420\n'.encode('ascii'))
        for _ in range(FPS):
            file.write(b'FRAME\n')
            file.write(pixels)
    temporary.replace(FIXTURE)
    print(FIXTURE)


if __name__ == '__main__':
    main()
