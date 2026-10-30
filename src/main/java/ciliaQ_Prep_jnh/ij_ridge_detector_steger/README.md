# Vendored Ridge Detection (Steger line detector)

This folder contains a **vendored (copied-in) and modified** copy of the Steger ridge
/ line detection code from the ImageJ **Ridge Detection** plugin.

## Original source

- **Project:** Ridge Detection plugin for ImageJ
- **Repository:** https://github.com/thorstenwagner/ij-ridgedetection
- **Copied from:** `src/main/java/de/biomedical_imaging/ij/steger/`
 (https://github.com/thorstenwagner/ij-ridgedetection/tree/master/src/main/java/de/biomedical_imaging/ij/steger)
- **Date copied:** 2026-10-29 5:08 PM (Pacific Time)
- **Upstream commit:** ad4f34d
- **Release (from POM):** 1.4.2-SNAPSHOT

## Authors / copyright (original)

- Thorsten Wagner — ImageJ Java plugin (2014–2015)
- Carsten Steger — original C code (1996–1998)
- R. Balasubramanian — detect-lines code to incorporate within GRASP (1999)

Full copyright notices are preserved in the header of each source file.

### Information from https://imagej.net/plugins/ridge-detection:

Method: 

> Steger, C. (1998). An unbiased detector of curvilinear structures. IEEE Transactions on Pattern Analysis and Machine Intelligence, 20(2), 113–125. doi:10.1109/34.659930

Implementation:

> Wagner, T., Hiner, M., & Xraynaud. (2017, August 20). thorstenwagner/ij-ridgedetection: Ridge Detection 1.4.0 (Version v1.4.0). Zenodo. doi:10.5281/ZENODO.845874

## License

The original code is licensed under the **GNU General Public License, version 2 (GPL-2.0)**
(or, at your option, any later version), as stated in every source file header:

> This program is free software: you can redistribute it and/or modify it under the terms
> of the GNU General Public License as published by the Free Software Foundation, either
> version 2 of the License, or (at your option) any later version.
> ... distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY ...
> See <http://www.gnu.org/licenses/gpl-2.0.html>.

CiliaQ Preparator is itself GPL-licensed, so this vendored copy is license-compatible.
**The GPL headers in each file must be kept intact.** A copy of GPL-2.0 is available at
the link above and should accompany this distribution.



## Why this code is vendored (instead of depending on the installed plugin)

The installed Ridge Detection plugin produces **non-reproducible results**: the same image
with the same parameters, in the same Fiji session, can yield a different set of detected
lines between runs (observed e.g. as 12 vs 14 filaments on identical input). For
quantitative, mechanistic analysis we require **bit-for-bit reproducible** segmentation,
so the code was copied in and corrected here. See "Modifications" below.

## Modifications made to the original

All changes are confined to determinism fixes; the detection algorithm is otherwise
unchanged. Every modified file carries an inline comment marked `// CiliaQ-MOD:` at the
point of change.

### Background

The original code produced **non-reproducible results**: the same image with the same
parameters, in the same Fiji session, could return a different set of detected lines between
runs (observed as e.g. 12–16 filaments and differing foreground voxel counts on byte-identical
input). The causes were several order-dependent constructs — non-total-order comparators, an
unreset static counter, and (most importantly) iteration over hash collections whose order is
not defined. These were corrected as listed below. After the fixes, repeated runs — including
across a full Fiji/JVM restart — produce identical output.

### Changes

1. **Package renamed** from `de.biomedical_imaging.ij.steger` to
`ciliaQ_Prep_jnh.ij_ridge_detector_steger` in every file, so this vendored copy does not
collide (split-package) with any installed Ridge Detection plugin on the classpath.
*(This rename is specific to vendoring and is NOT part of the upstream fix.)*

2. **`Crossref.compareTo` made a total order** (`Crossref.java`). The original compared only
`value` and returned 0 for equal values, leaving the sort order of equal-response starting
points undefined. On low-background images many candidates share the same response, so their
order after `Arrays.sort` — and hence which starting points the linker picks — was not
reproducible. Tie-breaking on `x` then `y` makes the ordering fully determined by
`(value, x, y)`.

3. **`Junction.compareTo` made a total order** (`Junction.java`). The original compared only
`(cont1, pos)` and returned 0 for junctions sharing both, leaving their order undefined.
`Arrays.sort(junc)` (in `Link`) and `Collections.sort(resultJunction)` (in `LineDetector`)
then ordered such ties arbitrarily, and the line-splitting loop (which groups junctions by
`cont1` and splits a line at each `pos`) could produce a different split — and thus a
different line count — between runs. Tie-break on `cont2`, then `x`, then `y` to make the
ordering total. The comparisons were also changed from integer subtraction
(`a - b`, which can overflow) to `Integer.compare` / `Float.compare` (overflow-safe).

4. **`Line.idCounter` reset at the start of `detectLines`** (`LineDetector.java`, `Line.java`).
`Line.idCounter` is a static counter the original never resets, so line IDs accumulate across
detections within one JVM session. Several internal steps iterate `HashSet<Integer>` of these
IDs (iteration order depends on the integer values), so a different ID range gave different
processing order. A single `Line.resetCounter();` was added at the top of
`LineDetector.detectLines(...)` so every detection starts from id 0 and is reproducible.
(`Line.resetCounter()` already existed in the original but was never called.)

5. **Deterministic iteration of line-ID set in junction reconstruction** (`LineDetector.java`).
In `addAdditionalJunctionPointsAndLines`, the set of connected line IDs (`lineIds`) was a
`HashSet<Integer>` that is **iterated** to create new junctions. `HashSet<Integer>` iterates
in an order determined by the integer values, so the created junctions — and the resulting
lines — were not reproducible. Changed to `LinkedHashSet<Integer>` (insertion order, which is
driven by deterministic loops). *(The non-iterated sets `uniqueIDs` and
`alreadyProcessedJunctionPoints`, used only for `contains`/`add`, were left unchanged, as
their iteration order does not affect the result.)*



6. **Deterministic collections in overlap resolution** (`SlopeOverlapResolver.java`) — the
largest source of non-determinism. This resolver runs when `OverlapOption.SLOPE` is used
(i.e. when "Resolve crossings" is enabled). It stored lines and junctions in `HashSet<Line>`,
`HashSet<Junction>`, and `HashMap<Line, …>`. Because `Line` and `Junction` do **not** override
`hashCode()`, these collections iterate in **identity-hash order**, which varies between JVM
runs (it depends on object identity hashes, not on the data). Since the resolver builds the
output line set and order by iterating these collections — most critically `finalLines` in
`buildResolvedList`, whose iteration order becomes the output line order — the detected lines
were non-reproducible on identical input, and differed even across a JVM restart. **Every**
`HashSet`/`HashMap` in this class was changed to `LinkedHashSet`/`LinkedHashMap`, which
preserve insertion order (driven by deterministic loops over `junctions`/`lines`). Only the
collection type changed; `contains`/`add`/`get`/`put` semantics are identical.



### Scope / what was NOT changed

- The detection, linking, width, and correction mathematics are untouched.



## Observations and open questions (not addressed here)

These are pre-existing characteristics of the original algorithm that were noticed during the
determinism work but **deliberately left unchanged**, because they concern algorithmic
*correctness* (what the algorithm computes), not *reproducibility* (that it computes the same
thing every time), which was the goal here. They are recorded for future investigation.

1. **"No main line" fallback in `fixJunctions` (`LineDetector.java`).** At some junction
positions the code cannot identify a "main" (through-) line and falls back to connecting the
secondary lines pairwise. The original author flagged this with the comment
*"In manchen Fällen gibt es keine Hauptlinie... (bug im Algorithmus, ich bin aber nicht fähig
ihn zu finden)"* ("In some cases there is no main line... bug in the algorithm, but I'm not
able to find it"). A likely benign explanation is that, after lines are split at junctions in
`Link.compute_contours`, a point that was *interior* to a line becomes an *endpoint* of the two
split pieces, so no line reports it as interior — in which case the fallback is expected
behaviour rather than a bug. Whether this is ever harmful on real data is unknown and was not
measured here. The determinism fixes ensure this fallback now fires **consistently**, so even
if the underlying handling is imperfect, the output is reproducible.

2. **Coordinate (x/y) swap in `fixJunctions` (`LineDetector.java`).** The method begins by
swapping each junction's `x` and `y`:

```java
// "For some reason, the x and y coordinates are permuted"
float help = junction.x; junction.x = junction.y; junction.y = help;
```

The original author performs this swap without a documented reason. It suggests arow/col vs.
x/y convention mismatch between where junctions are created (Link.compute_contours, which sets
junc.x = posx[...] (row) and junc.y = posy[...] (col)) and where they are matched back to
lines (minDistance, which compares against l.col/l.row). This swap may be related to the
"no main line" fallback above (an incorrect coordinate match would prevent finding an interior
point). It was not changed, as doing so would alter algorithmic output and requires careful
validation against known structures. Flagged for future investigation.

## Reporting upstream

On 10/29/2026, we submitted a **pull request** (determinism fixes, https://github.com/thorstenwagner/ij-ridgedetection/pull/42)
with the ordering/determinism changes listed under "Modifications made to the original" above.

## Maintenance note

This is foreign code adopted for determinism reasons. Keep changes minimal and clearly
marked (`// CiliaQ-MOD:`) so they can be re-applied if the upstream code is updated, and so
the diff against the original stays auditable.
