# Spike measurements

The spike app shows a measurement line under its status line:

`device · fps · lowest fps · memory · open time`

- **fps:** frames drawn in the last second. It only updates while the map is drawing, so read it while moving the map.
- **lowest:** the slowest reading since the app started. From build 21 the rate comes from the gaps between consecutive frames over the last 30 frames, ignoring pauses over 250 ms, so it reflects motion only. (In build 20 the lowest value counted idle pauses and always read near 1; ignore it there.)
- **memory:** the app's proportional memory use (PSS), in MB.
- **open time:** from the app process starting to the first fully drawn map, in ms.

## Steps (the same on every device)

Use the Vulkan build, then repeat with the OpenGL build. Font Amiri, «نص النظام» mode, test labels off.

1. Close the app fully, then open it. Note the **open time**.
2. Tap «الهجرة», wait for the camera to stop, then pan and pinch-zoom continuously for about 20 seconds across the whole route.
3. Tap «غزوة بدر», then «بناء المسجد النبوي», then «الهجرة».
4. Drag the year bar from end to end a few times.
5. Take a screenshot of the measurement line.

## Results

Release builds (build 21), 2026-11-13, one run per row:

| Device | Renderer | Open time | fps while moving | Lowest fps | Memory |
| --- | --- | --- | --- | --- | --- |
| Oppo A55 (Android 13) | OpenGL | 3.5 s | 59 | 23 | 142 MB |
| Oppo A55 (Android 13) | Vulkan | 5.4 s | 60 | 39 | 205 MB |
| Galaxy A54 (Android 16) | OpenGL | 1.3 s | 60 | 56 | 128 MB |
| Galaxy A54 (Android 16) | Vulkan | 1.0 s | 60 | 45 | 241 MB |
| Huawei MatePad 11.5 (120 Hz) | OpenGL | 0.8 s | 119 | 89 | 88 MB |
| Huawei MatePad 11.5 (120 Hz) | Vulkan | 1.0 s | 120 | 63 | 181 MB |

The owner reports all six as smooth. The tablet's screen runs at 120 Hz, so its fps is out of 120.

Earlier debug build (build 20), for comparison:

| Device | Renderer | Open time | fps while moving | Lowest fps | Memory |
| --- | --- | --- | --- | --- | --- |
| Oppo A55 | OpenGL | 7.2 s | about 18–57, mostly 20–35 | not valid (build 20) | 165–196 MB |

Notes on the Oppo A55 OpenGL run (build 20, 2026-11-13): measured in glyph-PBF mode with Plex, not the system-text mode with Amiri that the steps ask for. Values read from a 53-second screen recording and four screenshots.
