# Spike measurements

The spike app shows a measurement line under its status line:

`device · fps · lowest fps · memory · open time`

- **fps:** frames drawn in the last second. It only updates while the map is drawing, so read it while moving the map.
- **lowest:** the slowest second seen since the app started, counting only seconds in which the map kept drawing.
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

| Device | Renderer | Open time | fps while moving | Lowest fps | Memory |
| --- | --- | --- | --- | --- | --- |
| Galaxy A54 | Vulkan | | | | |
| Galaxy A54 | OpenGL | | | | |
| Oppo A55 | Vulkan | | | | |
| Oppo A55 | OpenGL | | | | |
| Huawei MatePad 11.5 | Vulkan | | | | |
| Huawei MatePad 11.5 | OpenGL | | | | |
