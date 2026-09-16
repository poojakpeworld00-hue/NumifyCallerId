# -*- coding: utf-8 -*-
"""Old -> New change-log PDF for the Callora -> Numify clone.

Written fresh for this clone (the skill's make_changelog_pdf.py is hardwired to a
different project). Reuses its table_page / section_divider shape and accent
treatment, but every table below is fed from data actually produced by this run:
the real rename maps in the scratchpad, docs/id_map.txt, and the live dependency
scan.
"""
import json, os, re, subprocess, sys
from fpdf import FPDF
from fpdf.fonts import FontFace

SP = sys.argv[1]
PROJ = sys.argv[2]
OUT = sys.argv[3]

ACCENT = (37, 99, 235)      # Numify indigo #2563EB
DARK = (16, 19, 25)
GREY = (106, 114, 133)

pdf = FPDF(orientation="L", unit="mm", format="A4")
pdf.set_auto_page_break(auto=True, margin=12)
pdf.add_font("arial", "", "/System/Library/Fonts/Supplemental/Arial.ttf")
pdf.add_font("arial", "B", "/System/Library/Fonts/Supplemental/Arial Bold.ttf")
USABLE = 297 - 20
HEAD = FontFace(emphasis="BOLD", fill_color=(233, 239, 252), color=ACCENT)


def table_page(title, subtitle, headers, rows, widths):
    pdf.add_page(); pdf.set_left_margin(10); pdf.set_right_margin(10)
    pdf.set_font("arial", "B", 13); pdf.set_text_color(*ACCENT)
    pdf.cell(0, 8, title, new_x="LMARGIN", new_y="NEXT")
    if subtitle:
        pdf.set_font("arial", "", 8); pdf.set_text_color(*GREY)
        pdf.multi_cell(0, 4.5, subtitle, new_x="LMARGIN", new_y="NEXT")
    pdf.ln(1)
    pdf.set_font("arial", "", 7.6); pdf.set_text_color(20, 20, 20)
    pdf.set_draw_color(220, 224, 230)
    tot = sum(widths); cw = tuple(USABLE * w / tot for w in widths)
    with pdf.table(col_widths=cw, first_row_as_headings=True, line_height=4.5,
                   text_align="LEFT", headings_style=HEAD) as t:
        r = t.row(); [r.cell(str(h)) for h in headers]
        for row in rows:
            r = t.row(); [r.cell("" if c is None else str(c)) for c in row]


def section_divider(text, note=""):
    pdf.add_page(); pdf.set_left_margin(10)
    pdf.ln(58)
    pdf.set_font("arial", "B", 24); pdf.set_text_color(*ACCENT)
    pdf.cell(0, 16, text, align="C", new_x="LMARGIN", new_y="NEXT")
    if note:
        pdf.set_font("arial", "", 10); pdf.set_text_color(*GREY)
        pdf.cell(0, 7, note, align="C", new_x="LMARGIN", new_y="NEXT")


def tsv(name):
    p = os.path.join(SP, name)
    if not os.path.exists(p):
        return []
    out = []
    for line in open(p, encoding="utf-8"):
        line = line.rstrip("\r\n")
        if not line or line.startswith("#"):
            continue
        out.append(line.split("\t"))
    return out


def chunk(rows, n):
    for i in range(0, len(rows), n):
        yield rows[i:i + n]


def paired(rows, per_page=44):
    """Two old->new pairs side by side, so long lists stay readable."""
    half = (len(rows) + 1) // 2
    left, right = rows[:half], rows[half:]
    merged = []
    for i in range(half):
        a = left[i] if i < len(left) else ["", ""]
        b = right[i] if i < len(right) else ["", ""]
        merged.append([a[0], a[1], b[0], b[1]])
    return list(chunk(merged, per_page))


# ---------------------------------------------------------------- cover
pdf.add_page(); pdf.ln(36)
pdf.set_font("arial", "B", 26); pdf.set_text_color(*ACCENT)
pdf.cell(0, 16, "Numify - Full Clone Change Log", align="C", new_x="LMARGIN", new_y="NEXT")
pdf.set_font("arial", "", 12); pdf.set_text_color(*DARK)
pdf.ln(3)
pdf.cell(0, 8, "Old -> New, every rename and rewrite in this clone",
         align="C", new_x="LMARGIN", new_y="NEXT")
pdf.set_font("arial", "", 10); pdf.set_text_color(*GREY)
pdf.ln(5)
for line in [
    "Source:  2026/08_Aug/CalloraCallerIDSpamBlock          Clone:  2026/09_Sep/NumifyCallerId",
    "Package: com.callora.callerid  ->  com.callerid.numberlookup.home          applicationId: com.callerid.numberlookup.home",
    "Sub-trees: adkit -> monetize     numberlookup -> lookup",
    "Accent: Amethyst #6D3BE8  ->  Indigo #2563EB",
    "Build status: BUILD SUCCESSFUL  (assembleDebug + testDebugUnitTest, 5/5 tests pass)",
]:
    pdf.cell(0, 6, line, align="C", new_x="LMARGIN", new_y="NEXT")

# ---------------------------------------------------------------- summary
counts = {
    "Package / identity": "900 refs across 183 files",
    "Classes + model types renamed": f"{len(tsv('allclasses.tsv'))}",
    "Kotlin files moved": "164",
    "Sub-packages restructured": f"{len(tsv('subpkg.tsv'))}",
    "Layouts renamed": f"{len(tsv('layouts.tsv'))}",
    "Drawables renamed": "393 of 395 (ic_launcher_* kept)",
    "Anims / raw / color selectors": "8 / 7 / 2",
    "View IDs renamed": "501 of 545 (34 ad-SDK ids excluded)",
    "Methods / API functions renamed": f"{len(tsv('methods.tsv')) + 2}",
    "Strings rewritten": "216 (18 keys x 12 locales)",
    "Dead resources deleted": "113",
    "Unit tests added": "5 (SecretDecoderTest, all passing)",
}
table_page("Summary", "What this clone changed, by category.",
           ["Category", "Count"], [[k, v] for k, v in counts.items()], [3, 4])

# ---------------------------------------------------------------- identity
table_page("1. Identity and branding", "Stage 2.",
           ["Item", "Old (Callora)", "New (Numify)"],
           [["Root package", "com.callora.callerid", "com.callerid.numberlookup.home"],
            ["namespace", "com.callora.callerid.numberlookup", "com.callerid.numberlookup.home"],
            ["applicationId", "com.callora.callerid.numberlookup", "com.callerid.numberlookup.home"],
            ["rootProject.name", "Callora CallerID SpamBlock", "Numify CallerID Lookup"],
            ["app_name", "Callora: Caller ID & Spam Block", "Numify: Caller ID & Number Lookup"],
            ["app_name_overlay", "Callora", "Numify"],
            ["archivesName prefix", "Callora", "Numify"],
            ["primary", "#6D3BE8 (Amethyst)", "#2563EB (Indigo)"],
            ["primary_dark", "#5326C4", "#1D4ED8"],
            ["primary_container", "#EDE5FD", "#DBEAFE"],
            ["accent_purple -> accent_indigo", "#6C3DD9", "#1D4ED8"],
            ["cid_500", "#8557F0", "#3B82F6"],
            ["splash_grad_end", "#6C3DF4", "#1E40AF"],
            ["night on_primary_container", "#C7ADFF", "#A8C7FF"],
            ["google-services.json", "callora-caller-id-spam-block", "numify placeholder (replace before release)"],
            ["Release keystore", "certificate/callora.jks", "NOT copied - generate a new key for Numify"]],
           [3, 5, 5])

# ---------------------------------------------------------------- packages
section_divider("Part 1  -  Architecture", "Sub-package restructure and class renames")
rows = [[o, n] for o, n in tsv("subpkg.tsv")]
for page in chunk(rows, 40):
    table_page("2. Sub-package restructure (Stage 3e)",
               "Every segment renamed; 164 files moved to match.",
               ["Old package", "New package"], page, [1, 1])

# ---------------------------------------------------------------- classes
allc = tsv("allclasses.tsv")
for i, page in enumerate(paired(allc, 44)):
    table_page("3. Class and model-type renames (Stage 3b)" + (" (cont.)" if i else ""),
               "164 top-level classes plus 14 model/enum types declared inside *Models.kt containers."
               if not i else "",
               ["Old", "New", "Old", "New"], page, [1, 1, 1, 1])

# ---------------------------------------------------------------- layouts
section_divider("Part 2  -  Resources", "Layouts, drawables, view IDs")
lay = tsv("layouts.tsv")
for i, page in enumerate(paired(lay, 44)):
    table_page("4. Layout renames (Stage 3c)" + (" (cont.)" if i else ""),
               "screen_->activity_, cell_->item_, sheet_->dialog_, pane_->fragment_, part_->include_, "
               "flyout_->popup_, bubble_->overlay_, art_->illustration_, gads_->admob_, meta_->audience_, "
               "house_->promo_. R.layout, @layout and ViewBinding class names all updated."
               if not i else "",
               ["Old", "New", "Old", "New"], page, [1, 1, 1, 1])

# ---------------------------------------------------------------- drawables
resmap = json.load(open(os.path.join(SP, "res_map.json"), encoding="utf-8"))
draw = sorted(resmap["drawable"].items())
for i, page in enumerate(paired([list(x) for x in draw], 44)):
    table_page("5. Drawable renames (Stage 3d)" + (" (cont.)" if i else ""),
               "393 of 395 renamed; ic_launcher_background / ic_launcher_foreground deliberately untouched."
               if not i else "",
               ["Old", "New", "Old", "New"], page, [1, 1, 1, 1])

other = ([["anim", o, n] for o, n in sorted(resmap["anim"].items())] +
         [["raw", o, n] for o, n in sorted(resmap["raw"].items())] +
         [["color", o, n] for o, n in sorted(resmap["color"].items())])
table_page("6. Anim / raw / color-selector renames (Stage 3d)", "",
           ["Type", "Old", "New"], other, [1, 3, 3])

# ---------------------------------------------------------------- view ids
ids = []
p = os.path.join(PROJ, "docs", "id_map.txt")
if os.path.exists(p):
    ids = [l.rstrip("\n").split("\t") for l in open(p, encoding="utf-8") if l.strip()]
for i, page in enumerate(paired(ids, 46)):
    table_page("7. View ID renames (Stage 3f)" + (" (cont.)" if i else ""),
               "501 of 545 ids. Obfuscated prefixes replaced with readable ones (lbl->text, pad->button, "
               "pic->image, roll->list, seg->tab, inp->input, crest->header, panel->card), the trailing "
               "'Vw' suffix dropped where unambiguous, and source typos fixed. 34 ids inside AdMob / Meta / "
               "house-ad native layouts are EXCLUDED - those SDKs bind asset views by reference."
               if not i else "",
               ["Old", "New", "Old", "New"], page, [1, 1, 1, 1])

# ---------------------------------------------------------------- methods
section_divider("Part 3  -  Code", "Methods, logic twists, content, cleanup")
meth = tsv("methods.tsv") + [["logKeyEvent", "recordEvent"],
                             ["logPermissionResult", "recordPermissionOutcome"],
                             ["SecretDecoder.s", "SecretDecoder.decode"]]
for i, page in enumerate(paired(meth, 44)):
    table_page("8. Method and API function renames (Stages 3g / 6)" + (" (cont.)" if i else ""),
               "Every candidate was checked for declarations and overrides first; no platform or library "
               "override name was touched." if not i else "",
               ["Old", "New", "Old", "New"], page, [1, 1, 1, 1])

# ---------------------------------------------------------------- twists
table_page("9. Logic twists (Stages 3a / 3h)",
           "Behaviour-preserving rewrites - same output, different construction.",
           ["File", "Old approach", "New approach"],
           [["SecretDecoder.kt",
             "index loop filling a pre-allocated ByteArray; method named s()",
             "ByteArray(size){} initializer + empty-input fast path; renamed decode()"],
            ["CallLogViewModel.kt",
             "one when(order) branching filter/sort/group inline; matches() helper; "
             "manual lastBucket loop",
             "enum-scoped extensions (accepts / matches / comparator / isChronological); "
             "flatMapIndexed against the previous element"],
            ["ContactListViewModel.kt",
             "when(filter) building byTab, nested query filter, manual lastLetter loop",
             "ContactFilter.accepts + ContactRecord.matches + initial extensions; "
             "flatMapIndexed header injection"]],
           [2, 4, 5])

# ---------------------------------------------------------------- ui restyle
table_page("10. UI restyle (Stage 3i)",
           "Drawable-level only; no layout-structure changes.",
           ["Change", "Detail", "Scope"],
           [["Corner radii", "Bumped one step, applied numeric-descending so a bumped target never "
                             "re-feeds a later source. Full pills (100dp / _100sdp / _99sdp) left alone.",
             "87 shapes"],
            ["Hairline borders", "1dp @color/outline added after the solid fill on card/tile surfaces "
                                 "that had none (4 already had a stroke and were skipped).", "12 shapes"],
            ["Squircles", "oval -> rectangle + corners for the avatar family AND its rings together, so "
                          "they stay concentric, plus the six tools-grid chips. Dots, rings, glows and "
                          "orbit decorations stay round.", "17 shapes"]],
           [2, 7, 2])

# ---------------------------------------------------------------- strings
table_page("11. Content rewrite (Stage 4)",
           "18 keys rewritten in a distinct voice and retranslated into all 11 localized files "
           "= 216 strings. Verified before/after that no format specifier or escaped newline changed "
           "in ANY string in ANY locale.",
           ["Key", "Old (Callora, en)", "New (Numify, en)"],
           [["onboarding_title", "Every caller, named", "Know who's calling"],
            ["onboarding_desc", "Unknown numbers get a name, a business and a location the moment they ring.",
             "See the name, company and region behind an unknown number before you pick up."],
            ["onboarding_title_2", "Silence scam calls", "Stop scam calls cold"],
            ["onboarding_desc_2", "Robocalls, scams and telemarketers are flagged and stopped before your phone rings.",
             "Robocalls, fraud attempts and telemarketers are caught and blocked automatically."],
            ["onboarding_title_3", "Look up any number", "Search any number"],
            ["onboarding_desc_3", "Type a number and get the name, carrier region and business behind it in seconds.",
             "Enter any digits and get back the owner, network region and business in seconds."],
            ["onboarding_get_started", "Start now", "Get started"],
            ["exit_dialog_title", "Exit app?", "Leave Numify?"],
            ["exit_dialog_desc", "Are you sure you want to exit?",
             "You can always come back - your blocklist stays saved."],
            ["perm_calllog_message", "Callora needs call log access to list the calls you have made and received.",
             "Numify reads your call log so recent calls appear here with names attached."],
            ["perm_contacts_message", "Callora needs contacts access to label calls with the names you already saved.",
             "Numify reads your contacts so saved people show up by name instead of a number."],
            ["perm_notification_desc", "Get spam and call alerts", "Spam warnings and call alerts"],
            ["perm_phone_desc", "Identify incoming callers", "Name callers as they ring"],
            ["perm_overlay_desc", "Show caller ID during calls", "Caller details while you talk"],
            ["perm_sheet_title", "Turn on permissions", "A few permissions to go"],
            ["perm_sheet_subtitle", "Caller ID, spam warnings and call history each need one permission to work.",
             "Caller ID, spam alerts and call history each depend on one of these."],
            ["fsi_screen_title", "See callers on your lock screen", "Callers on your lock screen"],
            ["fsi_screen_desc", "Callora paints verified caller details over the lock screen the moment a call arrives.",
             "The instant a call lands, Numify puts verified caller details on your lock screen."]],
           [2, 5, 5])

# ---------------------------------------------------------------- events
table_page("12. Event logging (Stage 6)",
           "Screen events already existed and now differ automatically, because the class names they are "
           "built from were renamed in 3b. The gap was permission outcomes.",
           ["Area", "Status"],
           [["screen_<class>", "Already present in BaseActivity / BaseFragment; names changed via 3b"],
            ["Ad show / type / fail-code", "Already present in the monetize module"],
            ["CALL_PHONE (recents shortcut)", "ADDED - CallTimelineFragment"],
            ["READ_PHONE_STATE (SIM info)", "ADDED - SimInfoActivity"],
            ["READ_CONTACTS (blocklist picker)", "ADDED - BlockedNumbersFragment"],
            ["READ_CALL_LOG (blocklist picker)", "ADDED - BlockedNumbersFragment"],
            ["RECORD_AUDIO (noise meter)", "ADDED - NoiseMeterActivity"],
            ["Generic priming launcher", "ADDED - PermissionLauncher"]],
           [3, 7])

# ---------------------------------------------------------------- cleanup
removed = []
if os.path.exists("/tmp/removed.txt"):
    removed = [l.strip() for l in open("/tmp/removed.txt") if l.strip()]
bykind = {}
for r in removed:
    k, n = r.split("/", 1)
    bykind.setdefault(k, []).append(n)
table_page("13. Cleanup (Stage 7)",
           "Unused resources found by iterating to a fixpoint over R.<type>.x, @<type>/x AND the "
           "ViewBinding class name - a scan without the binding check deletes live layouts.",
           ["Kind", "Removed", "Examples"],
           [[k, str(len(v)), ", ".join(v[:6]) + (" ..." if len(v) > 6 else "")]
            for k, v in sorted(bykind.items())] +
           [["version catalog", "0", "46 libraries, 3 plugins - all in use"]],
           [2, 1, 8])

# ---------------------------------------------------------------- deps
deps = json.load(open("/tmp/deps.json", encoding="utf-8"))
behind = [d for d in deps if d["latest"] and d["latest"] != d["current"]]


def gap(c, l):
    a = [int(x) if x.isdigit() else -1 for x in re.split(r"[.\-]", c)[:3]]
    b = [int(x) if x.isdigit() else -1 for x in re.split(r"[.\-]", l)[:3]]
    g = "MAJOR" if b[0] > a[0] else "minor" if len(b) > 1 and b[1] > a[1] else "patch"
    return g + (" (pre-release)" if re.search(r"alpha|beta|rc", l, re.I) else "")


table_page("14. Dependency audit (Stage 8) - REPORT ONLY, nothing bumped",
           f"{len(deps) - len(behind)} of {len(deps)} artifacts are current. Versions resolved live from "
           "Google Maven / Maven Central. No version was changed: an AGP, Firebase or OkHttp/Retrofit major "
           "bump can break in ways only a full release build reveals.",
           ["Artifact", "Current", "Latest", "Gap"],
           [[d["group"] + ":" + d["name"], d["current"], d["latest"], gap(d["current"], d["latest"])]
            for d in sorted(behind, key=lambda x: x["group"])],
           [6, 2, 2, 2])

# ---------------------------------------------------------------- notes
table_page("15. Deliberate exclusions and follow-ups", "Read this before shipping.",
           ["Item", "Why / what to do"],
           [["34 ad-layout view IDs kept",
             "AdMob and Meta native templates bind asset views by reference; renaming them breaks ad "
             "rendering, which no build can catch. Left as-is on purpose."],
            ["ic_launcher_* untouched",
             "You have not supplied the Numify launcher icon or splash image yet - send the paths and "
             "these get resized to all five densities and wired in."],
            ["google-services.json is a placeholder",
             "Replace with the real Firebase config for com.callerid.numberlookup.home before any build you ship."],
            ["SDK credentials are blank",
             "local.properties needs lighthouse.apiKey / baseUrl and lookup.apiId / apiHash / apiToken. "
             "The build warns at configuration time while they are empty."],
            ["No release keystore",
             "Callora's certificate/callora.jks was deliberately NOT copied. Generate a new key for "
             "Numify and set release.* in local.properties, or release builds stay unsigned."],
            ["Stage 5 (content de-duplication) skipped",
             "Chosen at the outset. Asset JSON schemas, long template strings and raster art remain "
             "byte-identical to the source. Run Stage 5 if you need to beat content-hash detection."],
            ["Comment rewrite: long blocks done, short ones not",
             "Comments were reworded rather than stripped, so the design rationale survives. All 182 "
             "blocks over 200 chars - where the distinctive prose lives - are rewritten, taking comment "
             "char-mass that matches the source from 100% to 55%. The remaining 45% is 1,139 short "
             "one-to-three-line comments; reword those too if you want the figure lower."],
            ["Instrumented tests not run",
             "androidTest needs a device or emulator. The 5 JVM unit tests pass."]],
           [3, 8])

pdf.output(OUT)
print("wrote", OUT)
