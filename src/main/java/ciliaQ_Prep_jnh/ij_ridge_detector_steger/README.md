# Vendored Ridge Detection (Steger line detector)

This folder contains a **vendored (copied-in) and modified** copy of the Steger ridge
/ line detection code from the ImageJ **Ridge Detection** plugin.

## Original source

- **Project:** Ridge Detection plugin for ImageJ
- **Repository:** https://github.com/thorstenwagner/ij-ridgedetection
- **Copied from:** `src/main/java/de/biomedical_imaging/ij/steger/`
  (https://github.com/thorstenwagner/ij-ridgedetection/tree/master/src/main/java/de/biomedical_imaging/ij/steger)
- **Date copied:** 2026-10-29 5:08 PM (Pacific Time)
- **Upstream commit:** ad4f34dc81aafd85b01a5b08a8b093d54b700b14 from 2017-10-25 
  (https://github.com/thorstenwagner/ij-ridgedetection/commit/ad4f34dc81aafd85b01a5b08a8b093d54b700b14)
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

The installed Ridge Detection plugin produces **non-reproducible results**: the same image with the
same parameters can yield a different set of detected lines between runs, within one Fiji session and
across a Fiji restart. The causes are iteration over hash collections whose order is not defined, a
static counter that is never reset, and comparators that kept ties in input order (modifications 2–6).
Since quantitative analysis requires **bit-for-bit reproducible** segmentation, the code was copied in
and corrected here. After the fixes, repeated runs, including across a restart, give identical output.
Further bugs found along the way were fixed as well; several of them change which lines are detected
(see the list below).

## Modifications made to the original

- Modifications 1–7 rename the package, fix determinism or remove unused code, and leave the computed
  lines unchanged.
- Modification 8 sorts only the junctions found, so every line is cut at its junctions (many were not,
  depending on the threshold), and **changes the output**.
- Modification 9 rebuilds the overlap resolution, so each filament gives one line (one ridge came out
  as 3–4 copies), and **changes the output**.
- Modification 10 lets the caller pass the line width and **changes the output** when used.
- Modification 11 fixes an ArrayIndexOutOfBoundsException for sigma below 0.7 and changes nothing
  where the code worked.
- Modification 12 keeps the responses of lines that outgrow their buffer (they were set to 0) and
  **changes the output** of such lines.
- Modification 13 makes five steps faster on noisy images and leaves the computed lines unchanged.
- Modification 14 returns each line once; without overlap resolution one line per plane came out
  twice, so it **changes the output** there; with it (the default) the lines are the same.
- Modification 15 fixes two junction details of the overlap resolution and **changes the output** only where two keys collided.
- Modification 16 returns every junction found (one kind was dropped) and **changes the output**.
- Modifications 17 to 19 fix three bugs of the line extension and the width estimation and **change the output** slightly.
- Modification 20 keeps the angles of split lines without width estimation (no change in CiliaQ's output).
- Modification 21 measures the direction at each end of a line from that end and lets a tiny line in a junction
  take only one joint; it **changes the output**.

Every modified file carries an inline comment marked `// CiliaQ-MOD:` at the point of change.

### Changes

1. **Package renamed** from `de.biomedical_imaging.ij.steger` to
`ciliaQ_Prep_jnh.ij_ridge_detector_steger` in every file, so this vendored copy does not
collide (split-package) with any installed Ridge Detection plugin on the classpath.
*(This rename is specific to vendoring and is NOT part of the upstream fix.)*

2. **`Crossref.compareTo` made a total order** (`Crossref.java`). The original compared only
`value` and returned 0 for equal values. Since `Arrays.sort` is stable, equal-response starting
points kept the order in which they were collected, so their order depended on the collection
loop, not on the data. Tie-breaking on `x`, then `y`, makes the order depend on `(value, x, y)`
alone. Since the points are already collected in x-then-y order, the output is
unchanged; the tie-break protects the result if the collection order ever changes.

3. **`Junction.compareTo` made a total order** (`Junction.java`). The original compared only
`(cont1, pos)` and returned 0 for junctions sharing both. `Arrays.sort(junc)` (in `Link`) and
`Collections.sort(resultJunction)` (in `LineDetector`) are stable, so such ties kept the order
of the input; where that order varied between runs (hash iteration, modifications 5 and 6), the
line-splitting loop (which groups junctions by `cont1` and splits a line at each `pos`) could
produce a different split — and thus a different line count. Ties are broken on `cont2`, then `x`, then `y` to make the
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
`HashSet<Integer>` that is **iterated** to create new junctions. `HashSet<Integer>` iterates in an
order determined by the integer values, so before modification 4 the order changed with the ID
range, and with it the created junctions and the resulting lines. It is now a
`LinkedHashSet<Integer>`, which iterates in insertion order, driven by deterministic loops, so the
order no longer depends on the ID values at all. *(`uniqueIDs` in `fixJunctions` and
`alreadyProcessedJunctionPoints` were made `LinkedHashSet`s too, for consistency, although they are
only used for `contains`/`add`.)*

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
that is iterated; the plain hash sets and maps that modifications 9 and 13 add are only used for lookups.)*

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
Since Steger's linking cuts every line at its junctions, no line passes through a T-junction, so the
pairwise branch of `fixJunctions` ("no main line"), which the original author's comment calls a bug,
is the correct handling. Before this fix the main-line branch was taken where lines had been left uncut.

9. **Overlap resolution rebuilt** (`SlopeOverlapResolver.java`) — **changes the output.** The
original treated every line with a junction at both ends as the overlap area of a crossing, however
long, paired its neighbours straight through it, and emitted every proposed merge as its own line.
One plain ridge came out as 3–4 copies (also in the released 1.4.1), and in tangles one filament was
routed along another. `resolveByJunctionClusters` replaces the merge step:
   - line ends on junctions less than 2 px apart (in x and in y, also step by step) form a **cluster**;
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
   2 (ceil(2.5 σ) + 1) entries, which can be more than ceil(3 × 2.5 σ) for σ below 1. For σ below 0.7
   this overflowed the buffer; on real images σ 0.6 caused an `ArrayIndexOutOfBoundsException` in
   nearly every plane. The buffer now has max(ceil(3 × 2.5 σ),
   2 (ceil(2.5 σ) + 1)) entries; a larger buffer changes nothing where the old one sufficed, so the result is
   unchanged where the code worked.

12. **Responses kept when a line outgrows its buffer** (`Link.java`, `compute_contours`) — **changes
   the output** of lines longer than the initial buffer. The response array was reallocated, but the old
   values were copied onto themselves (`resp[o] = resp[o]`) instead of into the new array, so all
   responses collected so far became 0. They are now copied.

13. **Faster on noise, same output** (`LineDetector.java`, `SlopeOverlapResolver.java`). Five steps took a time
   that grew with the square of the lines or junctions. `fixJunctions` and the split step (`LineDetector`) and the
   line ends of a junction cluster (`SlopeOverlapResolver`) now find the lines and junctions near a point from pixel
   grids, in the order of the loops they replace; the pruning (`LineDetector`) and the junction prune of the overlap
   resolution take one pass. On a plane without signal (about 170,000 lines and 260,000 junctions), one plane then 
   took about 1 minute instead of over 15. On the example images tested and on noise, the output is identical.

14. **Each line returned once** (`Link.java`) — **changes the output** without overlap resolution. The split loop of
   `compute_contours` moves the last new piece into a split line's slot, so that line also stays at the end of the
   used part of the array, and the final loop read the whole array: one line per plane came out twice. Now the loop
   stops at `num_cont`, as Steger's C code does. With overlap resolution (the default) the resolver dropped the copy,
   so the lines are the same there.

15. **Two junction details of the overlap resolution** (`SlopeOverlapResolver.java`). The key that finds junctions of
   the same two lines at one point had no separators, so lines 1 and 234 gave the key of lines 12 and 34 there; now
   it has. And a junction is added to the set of updated junctions once, after both its lines are updated, instead of
   being changed while in the set. The first changes the output only where such keys collided; the second does not
   change it (tested with all identity hashes equal).

16. **Every junction returned** (`Link.java`) — **changes the output** where the first line of a plane runs into
   itself. The final loop of `compute_contours` read the whole pre-allocated junction array and skipped its empty
   padding by `cont1 == 0 && cont2 == 0`. A line that runs into itself gets a junction with both set to its own index,
   so the test also dropped every such junction of line 0, the line traced first (from the strongest point). The line
   was still split there, but `fixJunctions` and the overlap resolution never saw the junction. Now the loop reads the
   junctions found, `[0, num_junc)`, as Steger's C code returns them and as modification 14 does for the lines.
   In tests, no such junction occurred in cilia stacks; in stacks of coiled filaments a few occurred per run, and about
   half of those runs changed by a few voxels or one object.

17. **Junction index after a start extension into the same line** (`Link.java`) — **changes the output** in rare
   cases. When the extension at the start of a line runs into that same line, its points have just been moved up by
   the inserted points, but the new junction took the index from before the move, so the split cut the line too
   early. The line's other junctions are shifted ("Adapt indices of the previously found junctions"); this one now
   is too. Tested alone on top of modification 16, on a cilia stack and a stack of crossing filaments: the output
   changed by a few dozen voxels at most and by at most one object.

18. **Width gaps up to the last point** (`Width.java`, `fill_gaps`) — **changes the output**. Points without a width
   are filled from their neighbours. The test for "a point with a width follows the gap" was `j < num_points - 1`,
   so a gap that ended right before the last point was handled as a gap to the end: the last point's width was
   overwritten by the one before the gap, or, for a gap from the first point, the widths were filled from 0. Now
   `j < num_points`. Tested alone on top of modification 16, on the same two stacks: up to a few thousand voxels
   and a few objects changed.

19. **Width arrays cleared for every line** (`Width.java`, `compute_line_width`) — **changes the output**. The arrays
   of corrections, asymmetries and gradients are allocated once, reused from line to line and set only where a
   width is found. In a line without any width, the gap filling took the previous line's values, so *Correct
   position* shifted its points by another line's corrections. They are now cleared for each line. Tested as
   modification 18: up to a few thousand voxels and a few objects changed.

20. **Angles of split lines kept** (`LineDetector.java`, `addAdditionalJunctionPointsAndLines`) — no change in
   CiliaQ's output. The angles of the two pieces were copied only with *Estimate width* on; without it they were 0.
   CiliaQ reads no angles after this step.

21. **The direction at each end; one joint for a tiny line** (`SlopeOverlapResolver.java`) — **changes the output**.
   The direction in which a line arrives at a junction is measured from the end into the line. To tell the ends
   apart, the original tested whether the point lies within 2 px of the start. So on a line whose two ends lie within
   2 px of each other the end was measured from the start: its direction had no length (a bend of 180°, no joint),
   and whether such a line could be joined at an end depended on the direction it had been traced in. On a longer,
   nearly closed line the end took the start's direction. Now an end is the start only if it is the start point.
   Since both ends of such a tiny line can then take a joint, a line shorter than 2 line widths whose two ends lie
   at the same junction takes at most one: with a joint at each end, each bent up to 60°, a path through a 2–3 px
   fragment in a crossing could turn by up to 120° and bridge two filaments. A longer line, e.g. a loop that returns
   to its junction, may take a joint at each end. Tested together with the branch rule in CiliaQ Preparator (pieces
   shorter than 2 line widths are ignored as branches) on 45 cilia stacks and a stack of crossing filaments: noisy
   stacks gave about 10 % fewer objects, since fewer short pieces are split off; crossings were resolved as before;
   and a curl that returns to its junction stays part of its filament.

Besides these, some comments were polished (wording only, no change to the code).

### Scope / what was NOT changed

- The detection of line points (derivatives, thresholds, sub-pixel position), the width and
	position-correction formulas, and the linking algorithm are Steger's, as in the original.
- All changes to them are bug fixes listed above.
- The overlap resolution (`SlopeOverlapResolver`) is not part of Steger's method; its merge step was
	replaced (modifications 9, 10 and 21).

## How CiliaQ sets the thresholds it passes in (not a modification of this code)

The detector takes sigma and the upper and lower thresholds. CiliaQ Preparator derives them from the expected line
width and a *signal range* of the channel: contrast = sensitivity × range, converted to the detector's
second-derivative units (`RidgeSettings.thresholdForContrast`). In v0.2.1 the range is measured in tiles
(`ciliaQ_Prep_jnh.CiliaQPrepStatistics.SignalRange`): the median of the ranges (p99.9 − median; median − p0.1 for dark
lines) of the whole stack, a 3×3 grid and the same grid shifted by half a tile, leaving out tiles without a single
positive voxel in the raw channel. This was done for robustness: in a test, one bright artefact moved the whole-stack
range by up to ×16384, and in small crops the cilium itself filled the top 0.1 % and raised the thresholds until half
of it was missed. Nothing in this package changed for it.

## Reporting upstream

On 2026-10-29, we submitted a **pull request** with the determinism fixes, modifications 2–6
(https://github.com/thorstenwagner/ij-ridgedetection/pull/42).

Modifications 8, 9, 11, 12 and 14 to 21 are **not** reported yet. The bugs of 8 and 9 are also in the released 1.4.1;
those of 11, 12 and 14 to 21 are in the upstream code this copy was taken from. A second pull request waits until #42 is accepted, since
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
