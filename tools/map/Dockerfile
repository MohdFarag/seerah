# syntax=docker/dockerfile:1
# Toolchain image for the map build tool. It holds only the toolchain: the
# tool's code is mounted read-only at /src and run with PYTHONPATH.

# GDAL 3.13.3, Ubuntu "small" variant; digest of the multi-arch index, resolved
# with `docker buildx imagetools inspect` on 2026-11-15.
#
# felt/tippecanoe 2.79.0 (BSD-2-Clause, published 2025-07-24), commit
# 68ab8dcc229f95b8b25877697d5e8d66783af503. Built on the runtime base image so
# runtime shared libraries (libsqlite3-0, zlib1g, libstdc++6) match without
# adding extra runtime packages.
FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:64250faf833c06d4b21afce4c27190039ba7ab58d70f0eebc87cf77d929c0b40 AS tippecanoe-build

RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ make libsqlite3-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

ADD --checksum=sha256:719b6d7b98f6be8aef102c75960cf91f5cb8bdbb2516f442b5668dcf1e181265 \
    https://codeload.github.com/felt/tippecanoe/tar.gz/68ab8dcc229f95b8b25877697d5e8d66783af503 \
    /tmp/tippecanoe.tar.gz

RUN mkdir -p /tmp/tippecanoe \
    && tar -xzf /tmp/tippecanoe.tar.gz -C /tmp/tippecanoe --strip-components=1 \
    && make -C /tmp/tippecanoe -j"$(nproc)" tippecanoe tile-join \
    && install -s -m 0755 /tmp/tippecanoe/tippecanoe /usr/local/bin/tippecanoe \
    && install -s -m 0755 /tmp/tippecanoe/tile-join /usr/local/bin/tile-join \
    && install -D -m 0644 /tmp/tippecanoe/LICENSE.md /usr/share/licenses/tippecanoe/LICENSE.md \
    && rm -rf /tmp/tippecanoe /tmp/tippecanoe.tar.gz

# jblindsay/whitebox-tools v2.4.0 (MIT, published 2024-05-22), commit
# 28ed01f506f2024cfb52db85348b887f2ded4e5d. Built on rust:1.99.1-bookworm
# (released 2026-10-15; Debian Bookworm glibc 2.36 is forward-compatible with
# Ubuntu 26.04 glibc 2.43).
#
# Upstream Cargo.lock ships a broken tsp-rs 0.1.0 entry (only used by
# whitebox-plugins), which causes `cargo build --locked` to fail. Inside this
# build stage we save the upstream Cargo.lock, restrict Cargo.toml workspace
# members to the five core crates (dropping whitebox-plugins and
# whitebox-runner), run `cargo update --workspace` to prune unused lock
# entries, and assert via awk + comm -23 that every remaining [[package]]
# record (name, version, source, checksum) is an exact verbatim match in the
# saved upstream Cargo.lock before building whitebox_tools with `--locked`.
FROM rust:1.99.1-bookworm@sha256:e6f2cff35d0f309acf8433b4f05878ed77092602c5065cc81801177da0bb3a9c AS whitebox-build

ADD --checksum=sha256:898e24b73c5a5145071029c558f465f58b1742d0e15f55f77cd5fe112c347951 \
    https://codeload.github.com/jblindsay/whitebox-tools/tar.gz/28ed01f506f2024cfb52db85348b887f2ded4e5d \
    /tmp/whitebox-tools.tar.gz

RUN <<'EOF'
set -eu
mkdir -p /tmp/whitebox-tools
tar -xzf /tmp/whitebox-tools.tar.gz -C /tmp/whitebox-tools --strip-components=1
cd /tmp/whitebox-tools
cp Cargo.lock /tmp/upstream.Cargo.lock
sed -i 's|^members = .*|members = ["whitebox-common", "whitebox-lidar", "whitebox-raster", "whitebox-tools-app", "whitebox-vector"]|' Cargo.toml
grep -Fxq 'members = ["whitebox-common", "whitebox-lidar", "whitebox-raster", "whitebox-tools-app", "whitebox-vector"]' Cargo.toml
cargo update --workspace
extract_packages() {
    awk '
    function flush() {
        if (in_pkg) {
            if (name == "" || version == "") {
                bad = 1
                exit 1
            }
            printf "%s\t%s\t%s\t%s\n", name, version, source, checksum
        }
        in_pkg = 0; name = ""; version = ""; source = ""; checksum = ""
    }
    { gsub(/\r/, "") }
    /^\[\[package\]\]$/ { flush(); in_pkg = 1; next }
    /^\[/ { flush(); next }
    in_pkg && /^name = / { name = substr($0, 8); next }
    in_pkg && /^version = / { version = substr($0, 11); next }
    in_pkg && /^source = / { source = substr($0, 10); next }
    in_pkg && /^checksum = / { checksum = substr($0, 12); next }
    END {
        if (bad) exit 1
        flush()
    }
    ' "$1" > "$2.raw"
    LC_ALL=C sort -u "$2.raw" > "$2"
    rm -f "$2.raw"
}
extract_packages /tmp/upstream.Cargo.lock /tmp/upstream.tsv
extract_packages Cargo.lock /tmp/pruned.tsv
test -s /tmp/upstream.tsv
test -s /tmp/pruned.tsv
unexpected=$(LC_ALL=C comm -23 /tmp/pruned.tsv /tmp/upstream.tsv)
test -z "$unexpected"
cargo build --release --locked -p whitebox_tools
install -s -m 0755 target/release/whitebox_tools /usr/local/bin/whitebox_tools
install -D -m 0644 LICENSE.txt /usr/share/licenses/whitebox-tools/LICENSE.txt
cd /
rm -rf /tmp/whitebox-tools /tmp/whitebox-tools.tar.gz /tmp/upstream.Cargo.lock /tmp/upstream.tsv /tmp/pruned.tsv
EOF

# GDAL 3.13.3, Ubuntu "small" variant; digest of the multi-arch index, resolved
# with `docker buildx imagetools inspect` on 2026-11-15.
FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:64250faf833c06d4b21afce4c27190039ba7ab58d70f0eebc87cf77d929c0b40

# Compiled vector tiling and hydrology tools, placed before Python deps so
# Python updates do not rebuild C++ or Rust stages.
COPY --from=tippecanoe-build /usr/local/bin/tippecanoe /usr/local/bin/tippecanoe
COPY --from=tippecanoe-build /usr/local/bin/tile-join /usr/local/bin/tile-join
COPY --from=tippecanoe-build /usr/share/licenses/tippecanoe/LICENSE.md /usr/share/licenses/tippecanoe/LICENSE.md
COPY --from=whitebox-build /usr/local/bin/whitebox_tools /usr/local/bin/whitebox_tools
COPY --from=whitebox-build /usr/share/licenses/whitebox-tools/LICENSE.txt /usr/share/licenses/whitebox-tools/LICENSE.txt

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

# Writable mount points for the cache, scratch space and output.
RUN mkdir -m 1777 /cache /work /out

# One thread per process and a fixed GDAL block cache (MiB), so runs are
# deterministic and the memory budget is predictable.
ENV GDAL_NUM_THREADS=1 \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    TIPPECANOE_MAX_THREADS=1 \
    RAYON_NUM_THREADS=1 \
    GDAL_CACHEMAX=256 \
    PYTHONDONTWRITEBYTECODE=1
