# syntax=docker/dockerfile:1
# Toolchain image for the map build tool. It holds only the toolchain: the
# tool's code is mounted read-only at /src and run with PYTHONPATH.

# GDAL 3.13.3, Ubuntu "small" variant; digest of the multi-arch index, resolved
# with `docker buildx imagetools inspect` on 2026-11-15.
FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:64250faf833c06d4b21afce4c27190039ba7ab58d70f0eebc87cf77d929c0b40

# pip 26.2.2 wheel (PyPI, uploaded 2026-10-20), run straight from the wheel so
# nothing comes from Ubuntu's archive. SHA-256 from the PyPI release page.
ADD --checksum=sha256:fd7f4dfa8190e014013b9f08f3e973f4e4c26ffd179a204895533a7254813a70 \
    https://files.pythonhosted.org/packages/3f/06/3e4420302363fbb71be3c7f35ce042f54bfca330e3e66b3a2e3c4cca579f/pip-26.2.2-py3-none-any.whl \
    /tmp/pip.whl/pip-26.2.2-py3-none-any.whl

# Hash-locked Python dependencies. requirements.txt is generated from requirements.in
# by uv 0.13.6 (released 2026-10-27) in a throwaway container; uv is not in this image.
# To regenerate, run from the repository root with a Docker whose `docker run`
# supports `--mount type=image`:
#
#   docker run --rm --user "$(id -u):$(id -g)" --security-opt label=disable \
#     -e UV_CACHE_DIR=/tmp/uv --tmpfs /tmp \
#     -e UV_CUSTOM_COMPILE_COMMAND='see the command in tools/map/Dockerfile' \
#     --mount type=image,src=ghcr.io/astral-sh/uv:0.13.6,dst=/uvimg \
#     -v "$PWD/tools/map:/m" -w /m ghcr.io/osgeo/gdal:ubuntu-small-3.13.3 \
#     /uvimg/uv pip compile --python-platform x86_64-unknown-linux-gnu \
#       --generate-hashes requirements.in -o requirements.txt
COPY requirements.txt /tmp/requirements.txt
RUN PYTHONPATH=/tmp/pip.whl/pip-26.2.2-py3-none-any.whl python3 -m pip install \
        --break-system-packages --require-hashes --no-cache-dir -r /tmp/requirements.txt \
    && rm -rf /tmp/pip.whl /tmp/requirements.txt

# go-pmtiles v1.31.2 (GitHub release, 2026-07-22); SHA-256 of the Linux x86_64
# archive, equal to the digest GitHub publishes for the asset.
ADD --checksum=sha256:3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f \
    https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_x86_64.tar.gz \
    /tmp/go-pmtiles.tar.gz
RUN mkdir -p /tmp/go-pmtiles /usr/share/licenses/go-pmtiles \
    && tar -xzf /tmp/go-pmtiles.tar.gz -C /tmp/go-pmtiles \
    && install -m 0755 /tmp/go-pmtiles/pmtiles /usr/local/bin/pmtiles \
    && install -m 0644 /tmp/go-pmtiles/LICENSE /usr/share/licenses/go-pmtiles/LICENSE \
    && rm -rf /tmp/go-pmtiles /tmp/go-pmtiles.tar.gz

# Writable mount points for the cache, scratch space and output.
RUN mkdir -m 1777 /cache /work /out

# One thread per process and a fixed GDAL block cache (MiB), so runs are
# deterministic and the memory budget is predictable.
ENV GDAL_NUM_THREADS=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 GDAL_CACHEMAX=256 PYTHONDONTWRITEBYTECODE=1
