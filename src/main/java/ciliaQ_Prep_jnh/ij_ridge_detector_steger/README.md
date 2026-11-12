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
lines between runs on identical input. For
quantitative, mechanistic analysis we require **bit-for-bit reproducible** segmentation,
so the code was copied in and corrected here. See "Modifications" below. The vendored
copy also contained three further bugs. Two of them change which lines are detected and are
fixed as modifications 8 and 9. The third caused an ArrayIndexOutOfBoundsException for sigma
values below 0.7 and is fixed as modification 11.

## Modifications made to the original

Modifications 1–7 fix determinism or remove unused code and leave the computed lines unchanged.
Modifications 8 and 9 fix two bugs and **change the output**, see *Observations and their investigation*.
Modification 11 fixes a third bug (sigma values below 0.7 caused an ArrayIndexOutOfBoundsException)
and changes nothing where the code worked.
Modification 12 fixes a fourth bug: a self-assignment in the response-array reallocation silently
zeroed accumulated response values on lines exceeding the initial buffer size; the fix restores
correct responses and **changes the output** wherever such reallocation occurred. Every modified file
carries an inline comment marked `// CiliaQ-MOD:` at the point of change.

### Background

The original code produced **non-reproducible results**: the same image with the same
parameters, in the same Fiji session, could return a different set of detected lines between
runs on byte-identical input. The cause was several order-dependent constructs: non-total-order comparators, an
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
(`Line.resetCounter()` already existed in the original but was never called; it was made
`public` so `LineDetector` can call it.)

5. **Deterministic iteration of line-ID set in junction reconstruction** (`LineDetector.java`).
In `addAdditionalJunctionPointsAndLines`, the set of connected line IDs (`lineIds`) was a
`HashSet<Integer>` that is **iterated** to create new junctions. `HashSet<Integer>` iterates
in an order determined by the integer values, so the created junctions — and the resulting
lines — were not reproducible. Changed to `LinkedHashSet<Integer>` (insertion order, which is
driven by deterministic loops). *(`uniqueIDs` in `fixJunctions` was switched to
`LinkedHashSet` as well, for consistency, although it is only used for `contains`/`add`.
`alreadyProcessedJunctionPoints` was left a `HashSet`: it is never iterated.)*

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
*(`buildResolvedList` was later replaced by modification 9. The rule still holds for every collection
that is iterated; the two plain `HashSet`s it adds are only used for lookups.)*

7. **Stand-alone plugin GUI removed.** `Lines_.java` (the Ridge Detection menu command and its
dialog), `GenericDialogPlus.java` and `ResetToDefaultListener.java` were deleted. CiliaQ calls
`LineDetector.detectLines(...)` directly and never uses them; the copy compiles without them. This
also removes a second class named `Lines_`, which follows ImageJ's plugin-naming convention. To
re-sync with upstream, ignore these three files.


8. **Junction sort limited to the junctions found** (`Link.java`) — **changes the output.**
`compute_contours` sorted the whole pre-allocated junction array (`Arrays.sort(junc)`), including its
empty padding, where Steger's C code sorts only the found junctions (`qsort(junc, num_junc, …)`). The
padding sorted to the front and pushed real junctions past `num_junc`, so the split loop never cut
those lines. The number of lines left uncut depended on how full the array was, so the result changed
with the detection threshold. Now `Arrays.sort(junc, 0, num_junc)`. The loop
that counts a line's junctions also read the array before checking its bound, and threw
`ArrayIndexOutOfBoundsException` whenever the array was exactly full; it now checks the bound first.
See *Observations and their investigation*, item 1.

9. **Overlap resolution rebuilt** (`SlopeOverlapResolver.java`) — **changes the output.** The
original treated every line with a junction at both ends as the overlap area of a crossing, however
long, paired its neighbours straight through it, and emitted every proposed merge as its own line.
One plain ridge came out as 3–4 copies (also in the released 1.4.1), and in tangles one filament was
routed along another. `resolveByJunctionClusters` replaces the merge step:
   - line ends on the same junction form a **cluster**;
   - a line between two clusters and at most **4 line widths** long is the **overlap piece** of a
     crossing. Two filaments of width *w* crossing at angle θ overlap over about *w* / sin(θ/2) —
     1.4 widths at 90°, 2.6 at 45°, 3.9 at 30° — so crossings down to about 30° are traced through.
     The width is the detector's estimate, or, with *Estimate width* off, the nominal width from sigma;
   - candidate **joints** — between ends of one cluster, or through an overlap piece — are scored
     with the original's `straightCalc` measure over the path they build, and accepted straightest
     first: **one partner per line end**, no cycles, **at most two filaments through one overlap
     piece**, and **only if the path bends by at most 60°** (a U-turn bends 180°, a T's side branch
     90°);
   - accepted joints are chained, so every line other than an overlap piece ends up in exactly one
     output line.

   The methods it replaces were removed. Tested on synthetic ridges with known geometry (single
   ridges, crossings, T-junctions, crossing arcs, parallel ridges, tips meeting at an angle) and on
   ICC and tissue IF images. Each filament now gives one output line instead of 2–4 copies, crossings
   down to about 30° are traced through as two continuous filaments, and incorrect joins at crossings
   are largely removed. **Not solved:** crossings shallower than about 30° are split into pieces
   instead of traced through, and two filaments meeting tip to tip at a bend below 60° are joined into
   one output line.

10. **The overlap resolution can be given the line width** (`LineDetector.java`,
   `SlopeOverlapResolver.java`) — **changes the output** when used. Modification 9 infers the nominal
   width from sigma with the bar formula (w = 2√3 (σ − 0.5)). Cilia are thinner than the
   resolution, so across the line they are Gaussian, and a sigma near 1 suits them; the formula then
   gives 1.7 px, and a crossing's overlap piece (up to 4 widths) is no longer seen as one, so crossings
   are not traced through. `LineDetector.setLineWidth(w)` passes the user's width; the resolver then
   uses it (`SlopeOverlapResolver.forWidth`). Without it, the behaviour of modification 9 is unchanged.
   CiliaQ Preparator's ridge detection always passes the width.

11. **The search-line buffer fits small sigmas** (`Link.java`, `Width.java`) — no change in the output where
   the code worked before. The line extension in `Link.compute_contours` and the width estimation in
   `Width` collect the pixels of a search line of length 2.5 σ with `Width.bresenham`, into a buffer of
   ceil(3 × 2.5 σ) entries. Bresenham writes one pixel per step plus at most one sideways, up to
   2 (ceil(2.5 σ) + 1) entries, which is more than ceil(3 × 2.5 σ) for σ below 0.7. For σ below 0.7
   this overflows the buffer; on real images σ 0.6 caused an `ArrayIndexOutOfBoundsException` in
   nearly every plane. The buffer now has max(ceil(3 × 2.5 σ),
   2 (ceil(2.5 σ) + 1)) entries; from σ 0.7 up that is the old size, so the result is unchanged.

12. Fixed a **response-array reallocation bug** in the line-linking step (`Link.compute_contours`).
  The original code self-assigned (`resp[o] = resp[o]`)
  into a freshly allocated array, silently zeroing all accumulated response values
  whenever a line exceeded the initial buffer size. Responses are now copied correctly.

### Scope / what was NOT changed

- The detection, width and position-correction mathematics are untouched. Linking is unchanged
except for the junction sort (8); overlap resolution was rebuilt (9) and can take the line width (10);
the search-line buffer fits small sigmas (11).

## How CiliaQ sets the thresholds it passes in (not a modification of this code)

The detector takes sigma and the upper and lower thresholds. CiliaQ Preparator derives them from the expected line
width and a *signal range* of the channel: contrast = sensitivity × range, converted to the detector's
second-derivative units (`RidgeSettings.thresholdForContrast`). In v0.2.1 the range is measured in tiles
(`ciliaQ_Prep_jnh.CiliaQPrepStatistics.SignalRange`): the median of the ranges (p99.9 − median; median − p0.1 for dark
lines) of the whole stack, a 3×3 grid and the same grid shifted by half a tile, leaving out tiles without a single
positive voxel in the raw channel. This was done for robustness: In a test, one bright artefact moved the whole-stack
range by up to ×16384, and in small crops the cilium itself filled the top 0.1 % and raised the thresholds until half
of it was missed. Nothing in this package changed for it.

## Observations and their investigation

Two characteristics of the original algorithm were noticed during the determinism work and
investigated afterwards (2026-10-30). The first turned out to be the visible symptom of two bugs, now
fixed as modifications 8 and 9; the second is correct as it is.

1. **"No main line" fallback in `fixJunctions` (`LineDetector.java`) — a symptom of two bugs,
fixed as modifications 8 and 9.** Where no line passes *through* a junction point, the code connects the
lines that end there pairwise. The original author called this a bug (*"In manchen Fällen gibt es
keine Hauptlinie… (bug im Algorithmus, ich bin aber nicht fähig ihn zu finden)"*). Before the fixes
it was the usual path. Investigation found two reasons, neither of them a bug in this branch itself:

- **Steger's linking cuts every line at its junctions, by design** (`Link.compute_contours`, *"split
the lines at the junction points"*). A T-junction is therefore three line ends meeting at a point,
and no line passes through it. The pairwise "fallback" is the correct handling; the main-line branch
only found lines that should have been cut and were not, because of the junction-sort bug.
- **The junction-sort bug (modification 8)** left many junctions' lines uncut, so they stayed whole
where they should have been split. Junctions with a single line end were then dropped. After the fix,
every junction has two or more line ends.

The overlap resolution that runs after this step had a second, independent bug: modification 9.

2. **Coordinate (x/y) swap in `fixJunctions` (`LineDetector.java`) — correct and required.**
`Link.compute_contours` stores junctions in Steger's (row, column) order — `junc.x = posx[…]` /
`row[…]`, `junc.y = posy[…]` / `col[…]` — while `minDistance` compares them with `l.col` / `l.row`,
i.e. image x = column, y = row. The swap converts one into the other.
Not a bug; the code comment now says why (`CiliaQ-MOD`, comment only).

## Reporting upstream

On 2026-10-29, we submitted a **pull request** (determinism fixes, https://github.com/thorstenwagner/ij-ridgedetection/pull/42)
with the ordering/determinism changes listed under "Modifications made to the original" above.

Modifications 8, 9, 11 and 12 are **not** reported yet. The bugs of 8 and 9 are also in the released 1.4.1; those of 11
and 12 are in the upstream code this copy was taken from. A second pull request waits until #42 is accepted, since
the plugin is listed as unmaintained.

## Maintenance note

This is foreign code adopted for determinism reasons and for cilia images. Keep changes minimal and clearly
marked (`// CiliaQ-MOD:`) so they can be re-applied if the upstream code is updated, and so the diff
against the original stays auditable.

Modification 9 replaces the merge step of `SlopeOverlapResolver` rather than patching it. When
re-syncing with upstream, keep `resolveByJunctionClusters` and its helpers (`addJoint`, `endPoint`,
`bendDeg`, `lineWidth`, `concatenate`, `copyArray`); the removed upstream methods are listed in the
comment at the top of that file. After any re-sync, check single ridges, crossings and T-junctions
on a test image: one line per filament, no copies.