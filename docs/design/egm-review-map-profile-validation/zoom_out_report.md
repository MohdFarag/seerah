# Initial zoom-out review

## Major — Define which outline errors the loader catches

The goal at line 5 promises early rejection of unusable outline references. Task 2 preserves paths to missing files and checks containment alone. An outline of `.` resolves to an existing directory inside the root and satisfies those rules. The build can therefore finish its expensive block work before the pack stage discovers the error. State which errors belong to each stage. If an existing directory should fail early, add one directory case at the loader boundary and keep deferred reading for missing in-root files.

## Nit — Make the precision of the northern edge observable

Task 1 gives a rounded example for an inclusive maximum. Its rejection case of 86 degrees would also pass with a slightly oversized cutoff. Take the accepted edge from the installed WebMercatorQuad definition and exercise the next representable latitude above it. This checks that fetch and build use the grid's actual domain with a small loader test.

## Architecture and reuse

No false compromise detected. Both CLI commands already call the same loader, the existing Profile exposes the expanded extent, and the grid already names its tile matrix set. The proposed checks can use these boundaries. The YAML isolation test can exercise the existing public parser without introducing a new configuration layer.
