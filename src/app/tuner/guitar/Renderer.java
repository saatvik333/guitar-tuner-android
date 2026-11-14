package app.tuner.guitar;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Draws one frame from a {@link Ui}. Depends on android.graphics only.
 *
 * Visual grammar (shared with Level): flat colour, hairline strokes, one accent, circles as the
 * primary shape, everything centred on a single column, outlined pills for actions, filled accent
 * circles for "on" states, theme name flanked by chevrons at the top.
 *
 * The instrument is a hairline dial. A bead rides its upper arc: left is flat, right is sharp, the
 * ring at twelve o'clock is the target. The scale is tanh-compressed (75 deg at the far end) so the
 * last cents before pitch get the most travel: 1.3 deg per cent at the centre.
 *
 * Layout adapts to the window: dial over tuning and strings when there is height, dial beside them
 * in short wide windows; the settings page scrolls.
 */
final class Renderer {
    static final int H_THEME_PREV = 1, H_THEME_NEXT = 2, H_SETTINGS = 3, H_TONE = 4, H_PAUSE = 5,
            H_DIAL = 6, H_BACK = 7, H_TUNING_PREV = 8, H_TUNING_NEXT = 9, H_MIC = 19,
            H_A4_PREV = 10, H_A4_NEXT = 11, H_TOL_PREV = 12, H_TOL_NEXT = 13,
            H_NEEDLE_PREV = 14, H_NEEDLE_NEXT = 15, H_NAMES_PREV = 16, H_NAMES_NEXT = 17,
            H_HAPTIC = 18, H_KEEPON = 20, H_STRING = 32,
            H_GUIDE = 21, H_GUIDE_SKIP = 22, H_GUIDE_AGAIN = 23, H_GUIDE_OPEN = 25,
            H_GSTRING = 40, H_SUGGEST = 27, H_GUIDE_AUTO = 28;

    static final float SCALE_DEG = 75f;     // bead angle at the far end of the scale
    static final float SCALE_CENTS = 58f;   // tanh knee: 50 cents sit at 52 deg
    /** The guide's meter compresses less than the dial's: beginners start far off. */
    static final double GUIDE_CENTS = 90.0;

    private static final String DASH = "–", SEP = "  ·  ";
    private static final String[] ASCII = new String[128];
    static {
        for (int i = 0; i < ASCII.length; i++) ASCII[i] = String.valueOf((char) i);
    }
    private static final int STRUM_MAX = 250;       // StrumAnalyzer searches within +-250 cents
    private static final String[] STRUM_LABELS = new String[2 * STRUM_MAX + 1];

    /** Where a wrapped string breaks; kept for as long as its string, width and font stay the same. */
    private static final class Wrap {
        String src, first, second;
        float maxW, size;
        Typeface tf;
        boolean one;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Typeface light, regular;
    private final Wrap[] wraps = new Wrap[16];
    private int wrapNext;
    private String sentenceSrc = "", sentence = "";   // first sentence of the guide's advice

    // set by draw() for the frame
    private Ui u;
    private float dp, sp;
    private float a = 1f;            // alpha multiplier for the layer being drawn
    private float left, usable, cx0; // horizontal safe area: left edge, width, centre
    private float hitDy;             // canvas translation of the layer being drawn, applied to hit rects
    private boolean tight;           // short window: header and bottom bar closer to the edges
    private float headerCy, barCy;
    private float clipT = Float.NEGATIVE_INFINITY, clipB = Float.POSITIVE_INFINITY;

    // set by drawGuidePage(): what the window leaves room for
    private boolean pillsShown, captionShown, titleShown, meterDetail, meterCompact;

    Renderer(Typeface light, Typeface regular) {
        this.light = light;
        this.regular = regular;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
    }

    static float beadOf(double cents) {
        return (float) Math.tanh(cents / SCALE_CENTS);
    }

    static float guideBeadOf(double cents) {
        return (float) Math.tanh(cents / GUIDE_CENTS);
    }

    void draw(Canvas c, Ui ui) {
        u = ui;
        dp = ui.dp;
        sp = ui.sp;
        left = ui.insetL;
        usable = Math.max(1f, ui.width - ui.insetL - ui.insetR);
        cx0 = left + usable / 2f;
        tight = (ui.height - ui.insetT - ui.insetB) < 420f * dp;
        headerCy = ui.insetT + (tight ? 26f : 32f) * dp;
        barCy = ui.height - ui.insetB - (tight ? 30f : 40f) * dp;
        ui.resetHits();
        Palette p = ui.pal;
        c.drawColor(p.bg);

        // Two tabs under one bottom bar: Easy (the strings, home) and Advanced (the dial). They
        // cross-fade in place; settings slides over either.
        float pt = ui.pageT, gt = ui.guideT;
        float tunerA = (1f - gt) * (1f - pt), guideA = gt * (1f - pt);
        if (tunerA > 0.001f) {
            a = tunerA;
            hitDy = -16f * dp * pt + 10f * dp * gt;
            c.save();
            c.translate(0f, hitDy);
            drawTunerPage(c, p, tunerA >= 0.5f);
            c.restore();
        }
        if (guideA > 0.001f) {
            a = guideA;
            hitDy = -16f * dp * pt + 10f * dp * (1f - gt);
            c.save();
            c.translate(0f, hitDy);
            drawGuidePage(c, p, guideA >= 0.5f);
            c.restore();
        }
        if (pt > 0.001f) {
            a = pt;
            hitDy = 24f * dp * (1f - pt);
            c.save();
            c.translate(0f, hitDy);
            drawSettingsPage(c, p, pt >= 0.5f);
            c.restore();
        }
        hitDy = 0f;
        if (pt < 0.999f) {
            a = 1f - pt;
            drawBottomBar(c, p, pt < 0.5f);
        }
        a = 1f;
        drawHeader(c, p);
        drawPressed(c, p);
    }

    // ---- header ------------------------------------------------------------------------------

    private void drawHeader(Canvas c, Palette p) {
        float cy = headerCy, cx = cx0;
        text.setTypeface(regular);
        text.setTextSize(14f * sp);
        text.setColor(col(p.fg));
        float tw = text.measureText(u.themeName);
        textCentered(c, u.themeName, cx, cy);
        float off = tw / 2f + 22f * dp;
        chevron(c, cx - off, cy, -1, 5f * dp, 1.5f * dp, col(p.dim));
        chevron(c, cx + off, cy, 1, 5f * dp, 1.5f * dp, col(p.dim));
        hit(H_THEME_PREV, cx - off - 30f * dp, cy - 24f * dp, cx - 1f, cy + 24f * dp, cx - off, cy, 20f * dp);
        hit(H_THEME_NEXT, cx + 1f, cy - 24f * dp, cx + off + 30f * dp, cy + 24f * dp, cx + off, cy, 20f * dp);

        float sub = u.pageT;                                  // only settings is a page to go back from
        if (sub > 0.001f) {
            float bx = left + 30f * dp;
            a = sub;
            chevron(c, bx, cy, -1, 6f * dp, 1.5f * dp, col(p.fg));
            a = 1f;
            if (sub >= 0.5f) {
                hit(H_BACK, left, cy - 28f * dp, bx + 30f * dp, cy + 28f * dp, bx, cy, 22f * dp);
            }
        }
    }

    // ---- tuner page --------------------------------------------------------------------------

    private void drawTunerPage(Canvas c, Palette p, boolean active) {
        float top = headerCy + (tight ? 24f : 32f) * dp;
        float bottom = barCy - (tight ? 30f : 38f) * dp;
        float H = Math.max(1f, bottom - top);
        int n = u.stringCount;
        float st = u.stringsT;
        float tuningH = 48f * dp;
        float minR = 40f * dp;

        // Stacked: dial, tuning selector, strings. Layout decisions use the full string row so
        // the arrangement does not flip while the row fades in or out.
        float sr = stringRadius(usable - 24f * dp, n);
        float rowH = 2f * sr + 22f * dp;
        float rowDecide = st > 0.001f ? rowH : 0f;
        float stackedR = rawDialRadius(H - tuningH - rowDecide, usable);
        float colW = usable / 2f;
        float sideR = rawDialRadius(H, colW);
        if (stackedR < 100f * dp && sideR >= 32f * dp && sideR > Math.max(stackedR, 0f) * 1.15f) {
            // short wide window: dial on the left, tuning and strings on the right
            sideR = Math.min(sideR, 190f * dp);
            float ext = dialTopExtra(sideR);
            drawDial(c, p, left + colW / 2f, top + ext + Math.max(0f, (H - ext - 2f * sideR) / 2f) + sideR, sideR, colW, active);
            float rcx = left + colW + colW / 2f, rw = colW - 24f * dp;
            float sr2 = stringRadius(rw, n);
            float row2 = 2f * sr2 + 22f * dp;
            boolean rowFits = H >= tuningH + row2;
            float sh2 = rowFits ? row2 * st : 0f;
            float gy = top + Math.max(0f, (H - tuningH - sh2) / 2f);
            drawTuning(c, p, rcx, gy + tuningH / 2f, rw, active);
            if (rowFits && st > 0.001f) drawStrings(c, p, rcx, gy + tuningH + sh2 / 2f - 4f * dp * st, rw, sr2, active);
        } else {
            // Too short for everything: the string row goes first, then the tuning selector,
            // so nothing overlaps and every target stays where it is drawn.
            boolean showRow = st > 0.001f && stackedR >= minR;
            boolean showTuning = showRow || rawDialRadius(H - tuningH, usable) >= minR;
            float stringsH = showRow ? rowH * st : 0f;
            float tH = showTuning ? tuningH : 0f;
            float dialH = H - tH - stringsH;
            float R = clamp(rawDialRadius(dialH, usable), Math.min(minR, Math.max(16f * dp, (H - 36f * dp) / 2f)), 190f * dp);
            float ext = dialTopExtra(R);
            float cy = top + ext + Math.max(0f, (dialH - ext - 2f * R - 4f * dp) / 2f) + R;
            drawDial(c, p, cx0, cy, R, usable, active);
            if (showTuning) {
                float ty = top + dialH + tuningH / 2f;
                drawTuning(c, p, cx0, ty, usable - 24f * dp, active);
                if (showRow) drawStrings(c, p, cx0, ty + tuningH / 2f + stringsH / 2f - 4f * dp * st, usable - 24f * dp, sr, active);
            }
        }
    }

    private float stringRadius(float w, int n) {
        if (n <= 0) return 22f * dp;
        return clamp(w / n / 2f - 6f * dp, 12f * dp, 22f * dp);
    }

    /** Space above the dial circle taken by the scale and the flat/sharp marks. */
    private float dialTopExtra(float R) {
        return beadR(R) + 20f * dp;
    }

    private float beadR(float R) {
        return clamp(R * 0.07f, 7f * dp, 13f * dp);
    }

    /** Largest dial radius for a box, scale and marks included; may be below any usable size. */
    private float rawDialRadius(float h, float w) {
        return Math.min((h - 30f * dp - 6f * dp) / 2f, (w - 64f * dp) / 2f);
    }

    private static float theta(float cents) {
        return SCALE_DEG * (float) Math.tanh(cents / SCALE_CENTS);
    }

    private void drawDial(Canvas c, Palette p, float cx, float cy, float R, float colW, boolean active) {
        float b = beadR(R);
        float tr = b * 1.3f;
        float tune = u.tuneT;

        if (u.message.length() > 0 && R < 72f * dp) {
            // A message does not fit a small dial: it takes the dial's place.
            drawMessage(c, p, cx, cy - dialTopExtra(R) / 2f, Math.min(colW - 32f * dp, 300f * dp), 2f * R + dialTopExtra(R), active);
            return;
        }

        // scale: minor ticks every 5 cents, major every 10, ends at 50
        float r0 = R + b + 3f * dp;
        stroke.setStrokeWidth(1f * dp);
        for (int ct = -50; ct <= 50; ct += 5) {
            if (ct == 0) continue;
            boolean end = ct == -50 || ct == 50, major = ct % 10 == 0;
            float len = end ? 9f * dp : major ? 6f * dp : 3.5f * dp;
            stroke.setColor(col(end ? p.dim : p.line, end ? 0.8f : 1f));
            float th = (float) Math.toRadians(theta(ct));
            float sn = (float) Math.sin(th), cs = (float) Math.cos(th);
            c.drawLine(cx + r0 * sn, cy - r0 * cs, cx + (r0 + len) * sn, cy - (r0 + len) * cs, stroke);
        }
        // flat and sharp marks beyond the ends
        float gh = 10.5f * dp, rr = r0 + 7f * dp;
        float tl = (float) Math.toRadians(-theta(50) - 9f), trr = (float) Math.toRadians(theta(50) + 9f);
        int mark = col(p.dim, 0.9f);
        accidental(c, 2, cx + rr * (float) Math.sin(tl) - gh * 0.25f, cy - rr * (float) Math.cos(tl) - gh * 0.5f, gh, 1.2f * dp, mark);
        accidental(c, 1, cx + rr * (float) Math.sin(trr) - gh * 0.3f, cy - rr * (float) Math.cos(trr) - gh * 0.5f, gh, 1.2f * dp, mark);

        // dial, broken around the target ring
        float gap = (float) Math.toDegrees(Math.asin(Math.min(1f, (tr + 4f * dp) / R)));
        rect.set(cx - R, cy - R, cx + R, cy + R);
        stroke.setStrokeWidth(1.25f * dp);
        stroke.setColor(col(p.line));
        c.drawArc(rect, -90f + gap, 360f - 2f * gap, false, stroke);

        if (u.message.length() > 0) {
            drawMessage(c, p, cx, cy, R, active);
        } else if (R < 64f * dp) {
            if (active) hit(H_DIAL, cx - R, cy - R, cx + R, cy + R, 0, 0, -1f);
            drawReadoutSmall(c, p, cx, cy, R);
        } else {
            if (active) hit(H_DIAL, cx - R, cy - R, cx + R, cy + R, 0, 0, -1f);     // before any control on it
            drawReadout(c, p, cx, cy, R, active);
        }

        // bead
        float nt = u.noteT;
        if (nt > 0.001f) {
            float th = (float) Math.toRadians(SCALE_DEG * u.bead);
            float sn = (float) Math.sin(th), cs = (float) Math.cos(th);
            float bx = cx + R * sn, by = cy - R * cs;
            float lv = u.liveT;
            bead(c, p, bx, by, b, nt, lv);
            if (lv > 0.001f) {
                stroke.setStrokeWidth(1f * dp);
                stroke.setColor(col(p.onAccentText, nt * lv));
                float k = b * 0.34f;
                c.drawLine(bx - k * sn, by + k * cs, bx + k * sn, by - k * cs, stroke);
            }
        }

        // target ring on top: a marking on the glass
        targetRing(c, p, cx, cy - R, tr, tune, b * 2f);
    }

    private void drawReadout(Canvas c, Palette p, float cx, float cy, float R, boolean active) {
        float lv = u.liveT, tune = u.tuneT;
        float capSz = clamp(R * 0.075f, 10f * sp, 13f * sp);
        float noteSz = Math.min(R * 0.62f, 104f * sp);
        float centsSz = clamp(R * 0.13f, 13f * sp, 24f * sp);
        float detSz = clamp(R * 0.075f, 10f * sp, 13f * sp);

        // Large dial: positions in radii. Small dial: caption dropped, lines stacked by their sizes.
        boolean compact = R < 110f * dp;
        float base, centsBase, detBase;
        if (!compact) {
            base = cy + R * 0.16f;
            centsBase = cy + R * 0.40f + centsSz * 0.35f;
            detBase = cy + R * 0.63f + detSz * 0.35f;
        } else {
            float g = Math.max(6f * dp, R * 0.08f);
            float hb = noteSz * 0.7f + g + centsSz * 0.7f + g + detSz * 0.7f;
            base = cy - hb / 2f + noteSz * 0.7f + R * 0.04f;
            centsBase = base + g + centsSz * 0.7f;
            detBase = centsBase + g + detSz * 0.7f;
        }

        // caption
        if (!compact) {
            text.setTypeface(regular);
            text.setTextSize(capSz);
            text.setColor(col(p.dim));
            fitLine(c, u.caption, cx, cy - R * 0.44f, chord(R, 0.47f));
        }

        // note
        int noteCol = readoutColor(p, lv, tune);
        if (u.letter == 0 && u.readCheck) {
            check(c, cx, base - noteSz * 0.33f, noteSz * 0.3f, 1f, col(p.accentText));
        } else if (u.letter == 0) {
            text.setTypeface(light);
            text.setTextSize(noteSz * 0.8f);
            text.setColor(col(p.dim, 0.8f));
            c.drawText(DASH, cx, base - noteSz * 0.08f, text);
        } else {
            drawNote(c, p, u.letter, u.accidental, u.octave, cx, base, noteSz, noteCol);
        }

        // cents
        if (u.cents.length() > 0) {
            text.setTypeface(light);
            text.setTextSize(centsSz);
            text.setColor(col(u.centsAccent ? p.accentText : readoutColor(p, lv, tune)));
            fitLine(c, u.cents, cx, centsBase, chord(R, (centsBase - cy) / R));
        }
        boolean pill = u.readPill.length() > 0 && !compact;
        if (pill) {
            float py = cy + R * 0.8f;
            pill(c, p, u.readPill, cx, py, H_SUGGEST, active, false, chord(R, 0.86f), Math.min(34f * dp, R * 0.2f));
        }

        // hint and frequency on one line when they fit, else the hint alone
        text.setTypeface(regular);
        text.setTextSize(detSz);
        float maxW = chord(R, Math.min(0.95f, (detBase - cy) / R + 0.04f));
        boolean tapDial = compact && u.readPill.length() > 0;
        boolean both = !tapDial && u.hint.length() > 0 && u.freq.length() > 0;
        float w1 = 0f, w2 = 0f, w3 = 0f;
        if (both) {
            w1 = text.measureText(u.hint);
            w2 = text.measureText(SEP);
            w3 = text.measureText(u.freq);
        }
        String line;
        if (tapDial) {
            // no room for a button: the action is the hint, and the whole dial is its target
            line = u.readPill;
            if (active) hit(H_SUGGEST, cx - R, cy - R, cx + R, cy + R, cx, cy, R * 0.92f);
        } else if (both && w1 + w2 + w3 <= maxW) line = null;
        else line = u.hint.length() > 0 ? u.hint : u.freq;
        float y = detBase;
        if (line == null) {
            float x = cx - (w1 + w2 + w3) / 2f;
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(col(u.hintAccent ? p.accentText : p.dim));
            c.drawText(u.hint, x, y, text);
            text.setColor(col(p.dim));
            c.drawText(SEP, x + w1, y, text);
            c.drawText(u.freq, x + w1 + w2, y, text);
            text.setTextAlign(Paint.Align.CENTER);
        } else {
            boolean accent = u.hintAccent && line == u.hint || tapDial;
            text.setColor(col(accent ? p.accentText : p.dim));
            fitLine(c, line, cx, y, maxW);
        }
    }

    /** Width of the dial's interior at a height of {@code f} radii from the centre, with a margin. */
    private static float chord(float R, float f) {
        return 2f * R * (float) Math.sqrt(Math.max(0f, 1f - f * f)) * 0.9f;
    }

    /** Small dial: the note and the cents only. */
    private void drawReadoutSmall(Canvas c, Palette p, float cx, float cy, float R) {
        float lv = u.liveT, tune = u.tuneT;
        int col = readoutColor(p, lv, tune);
        boolean cents = u.cents.length() > 0 && R >= 30f * dp;
        float noteSz = R * (cents ? 0.62f : 0.8f);
        float centsSz = R * 0.24f;
        float g = R * 0.08f;
        float hb = noteSz * 0.7f + (cents ? g + centsSz * 0.7f : 0f);
        float base = cy - hb / 2f + noteSz * 0.7f;
        if (u.letter == 0) {
            text.setTypeface(light);
            text.setTextSize(noteSz * 0.8f);
            text.setColor(col(p.dim, 0.8f));
            c.drawText(DASH, cx, base - noteSz * 0.08f, text);
        } else {
            drawNote(c, p, u.letter, u.accidental, u.octave, cx, base, noteSz, col);
        }
        if (cents) {
            text.setTypeface(light);
            text.setTextSize(centsSz);
            text.setColor(col(col));
            float cb = base + g + centsSz * 0.7f;
            fitLine(c, u.cents, cx, cb, chord(R, (cb - cy) / R));
        }
    }

    /** Message and action without a dial, centred in a w x h box. */
    private void drawMessage(Canvas c, Palette p, float cx, float cy, float w, float h, boolean active) {
        float msz = 14f * sp;
        text.setTypeface(light);
        text.setTextSize(msz);
        text.setColor(col(p.fg));
        boolean pill = u.messageAction.length() > 0;
        float ph = Math.min(40f * dp, Math.max(28f * dp, h * 0.3f));
        float gap = 8f * dp;
        float textH = msz * 1.3f * lines(u.message, w);
        float total = textH + (pill ? gap + ph : 0f);
        float y0 = cy - total / 2f;
        wrapped(c, u.message, cx, y0 + textH / 2f, w, msz * 1.3f);
        if (pill) pill(c, p, u.messageAction, cx, y0 + textH + gap + ph / 2f, H_MIC, active, true, w, ph);
        else if (active) hit(H_MIC, cx - w / 2f, y0, cx + w / 2f, y0 + textH, 0f, 0f, -1f);
    }

    private int lines(String s, float w) {
        return wrap(s, w).one ? 1 : 2;
    }

    private void drawMessage(Canvas c, Palette p, float cx, float cy, float R, boolean active) {
        float msz = clamp(R * 0.11f, 12f * sp, 20f * sp);
        text.setTypeface(light);
        text.setTextSize(msz);
        text.setColor(col(p.fg));
        boolean pill = u.messageAction.length() > 0;
        float ph = Math.min(40f * dp, R * 0.42f);
        float my = pill ? cy - ph * 0.5f - Math.max(8f * dp, R * 0.08f) : cy;
        float mw = chord(R, 0.3f), lh = msz * 1.3f, midY = my - (pill ? msz * 0.65f : 0f);
        wrapped(c, u.message, cx, midY, mw, lh);
        if (pill) {
            pill(c, p, u.messageAction, cx, cy + ph * 0.5f + Math.max(4f * dp, R * 0.06f), H_MIC, active, true,
                    Math.min(1.3f * R, usable - 48f * dp), ph);
        } else if (active) {
            float half = lh * lines(u.message, mw) / 2f;
            hit(H_MIC, cx - mw / 2f, midY - half, cx + mw / 2f, midY + half, 0f, 0f, -1f);
        }
    }

    /** Letter centred at cx; accidental at its upper right, octave at its lower right. */
    private void drawNote(Canvas c, Palette p, char letter, int acc, String oct, float cx, float base, float size, int color) {
        text.setTypeface(light);
        text.setTextSize(size);
        text.setColor(col(color));
        String L = glyph(letter);
        float lw = text.measureText(L);
        c.drawText(L, cx, base, text);
        float cap = size * 0.70f;
        float x = cx + lw / 2f + size * 0.04f;
        if (acc != 0) accidental(c, acc, x, base - cap, cap * 0.56f, Math.max(1.2f * dp, size * 0.035f), col(color));
        if (oct.length() > 0) {
            text.setTypeface(regular);
            text.setTextSize(size * 0.25f);
            text.setColor(col(p.dim));
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(oct, x, base, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
    }

    /**
     * Sharp (1) or flat (2) drawn as strokes: Rubik has no music symbols. (x, top) is the glyph's
     * upper left corner, h its height.
     */
    private void accidental(Canvas c, int acc, float x, float top, float h, float w, int color) {
        stroke.setStrokeWidth(w);
        stroke.setColor(color);
        if (acc == 1) {
            float gw = h * 0.62f;
            float v1 = x + gw * 0.32f, v2 = x + gw * 0.70f;
            c.drawLine(v1, top + h * 0.06f, v1, top + h, stroke);
            c.drawLine(v2, top, v2, top + h * 0.94f, stroke);
            float y1 = top + h * 0.38f, y2 = top + h * 0.68f, sl = h * 0.07f;
            c.drawLine(x, y1 + sl, x + gw, y1 - sl, stroke);
            c.drawLine(x, y2 + sl, x + gw, y2 - sl, stroke);
        } else if (acc == 2) {
            float sx = x + h * 0.06f;
            c.drawLine(sx, top, sx, top + h, stroke);
            path.reset();
            path.moveTo(sx, top + h * 0.58f);
            path.cubicTo(sx + h * 0.22f, top + h * 0.40f, sx + h * 0.50f, top + h * 0.48f, sx + h * 0.42f, top + h * 0.68f);
            path.cubicTo(sx + h * 0.36f, top + h * 0.82f, sx + h * 0.16f, top + h * 0.92f, sx, top + h);
            c.drawPath(path, stroke);
        }
    }

    private void drawTuning(Canvas c, Palette p, float cx, float cy, float maxW, boolean active) {
        text.setTypeface(light);
        text.setTextSize(17f * sp);
        text.setColor(col(p.fg));
        float limit = Math.max(40f * dp, maxW - 80f * dp);
        float tw = Math.min(text.measureText(u.tuningName), limit);
        text.getFontMetrics(fm);
        fitLine(c, u.tuningName, cx, cy - (fm.ascent + fm.descent) / 2f, limit);
        float off = Math.min(Math.max(tw, 96f * dp) / 2f + 18f * dp, maxW / 2f - 8f * dp);
        chevron(c, cx - off, cy, -1, 5f * dp, 1.5f * dp, col(p.dim));
        chevron(c, cx + off, cy, 1, 5f * dp, 1.5f * dp, col(p.dim));
        if (active) {
            hit(H_TUNING_PREV, cx - off - 32f * dp, cy - 24f * dp, cx - 1f, cy + 24f * dp, cx - off, cy, 20f * dp);
            hit(H_TUNING_NEXT, cx + 1f, cy - 24f * dp, cx + off + 32f * dp, cy + 24f * dp, cx + off, cy, 20f * dp);
        }
    }

    private void drawStrings(Canvas c, Palette p, float cx, float cy, float maxW, float sr, boolean active) {
        int n = u.stringCount;
        if (n == 0) return;
        float base = a;
        a = base * u.stringsT;
        float s = Math.min(2f * sr + 16f * dp, maxW / n);
        float tsz = Math.min(15f * sp, sr * 0.78f);
        for (int i = 0; i < n; i++) {
            float x = cx + (i - (n - 1) / 2f) * s;
            float tt = u.sTunedT[i], tg = u.sTargetT[i], lk = u.sLockT[i];
            stringCircle(c, p, x, cy, sr, tt, tg, 0.5f);
            int tc = Palette.mix(Palette.mix(p.fg, p.accentText, tg), p.onAccentText, tt);
            letter(c, u.sLetter[i], u.sAcc[i], x, cy, tsz, col(tc));
            float st = u.strumT;
            if (lk > 0.001f && st < 0.5f) {
                fill.setColor(col(p.accent, lk * (1f - 2f * st)));
                c.drawCircle(x, cy + sr + 8f * dp, 2.5f * dp, fill);
            }
            if (st > 0.001f) {
                // strum check: a check, or how far off, under each string
                float my = cy + sr + 10f * dp, sc = u.sStrum[i];
                float base2 = a;
                a = base2 * st;
                if (Float.isNaN(sc)) {
                    text.setTypeface(regular);
                    text.setTextSize(11f * sp);
                    text.setColor(col(p.dim));
                    c.drawText(DASH, x, my + 4f * sp, text);
                } else if (Math.abs(sc) <= u.strumOk) {
                    check(c, x, my, 4f * dp, 1f, col(p.accentText));
                } else {
                    text.setTypeface(regular);
                    text.setTextSize(11f * sp);
                    text.setColor(col(p.fg));
                    c.drawText(strumLabel(Math.round(sc)), x, my + 4f * sp, text);
                }
                a = base2;
            }
            if (active) hit(H_STRING + i, x - s / 2f, cy - sr - 10f * dp, x + s / 2f, cy + sr + 14f * dp, x, cy, sr + 5f * dp);
        }
        a = base;
    }

    private void drawBottomBar(Canvas c, Palette p, boolean active) {
        float y = barCy;
        float x1 = left + usable * 0.2f, x2 = left + usable * 0.4f, x3 = left + usable * 0.6f, x4 = left + usable * 0.8f;
        iconSliders(c, x1, y, col(p.dim), col(p.bg));
        iconDial(c, p, x2, y, u.guideT < 0.5f);
        iconFork(c, p, x3, y, u.toneOn);
        iconPause(c, p, x4, y, u.paused);
        if (active) {
            float r = Math.min(30f * dp, usable / 10f);
            float hh = (tight ? 26f : 30f) * dp;
            hit(H_SETTINGS, x1 - r, y - hh, x1 + r, y + hh, x1, y, 22f * dp);
            hit(H_GUIDE_OPEN, x2 - r, y - hh, x2 + r, y + hh, x2, y, 22f * dp);
            hit(H_TONE, x3 - r, y - hh, x3 + r, y + hh, x3, y, 22f * dp);
            hit(H_PAUSE, x4 - r, y - hh, x4 + r, y + hh, x4, y, 22f * dp);
        }
    }

    // ---- settings page -----------------------------------------------------------------------

    private void drawSettingsPage(Canvas c, Palette p, boolean active) {
        float cx = cx0;
        float viewTop = headerCy + 24f * dp, viewBottom = u.height - u.insetB;
        float top = headerCy + 32f * dp;
        float rowsTop = top + 60f * dp;
        float minRow = 18f * sp + 60f * dp;
        float rh = Math.max(minRow, Math.min(96f * dp, (viewBottom - 16f * dp - rowsTop) / 6f));
        float contentBottom = rowsTop + 6f * rh + 16f * dp;
        u.settingsMaxScroll = Math.max(0f, contentBottom - viewBottom);
        float scroll = clamp(u.settingsScroll, 0f, u.settingsMaxScroll);

        c.save();
        c.clipRect(0f, viewTop, u.width, viewBottom);
        c.translate(0f, -scroll);
        float saveDy = hitDy;
        hitDy -= scroll;
        clipT = viewTop;
        clipB = viewBottom;

        text.setTypeface(light);
        text.setTextSize(26f * sp);
        text.setColor(col(p.fg));
        c.drawText("Settings", cx, top + 26f * sp, text);

        float y = rowsTop;
        selectorRow(c, p, "Reference pitch", u.a4Label, y, rh, H_A4_PREV, H_A4_NEXT, active);
        y += rh;
        selectorRow(c, p, "In-tune range", u.tolLabel, y, rh, H_TOL_PREV, H_TOL_NEXT, active);
        y += rh;
        selectorRow(c, p, "Needle", u.needleLabel, y, rh, H_NEEDLE_PREV, H_NEEDLE_NEXT, active);
        y += rh;
        selectorRow(c, p, "Note names", u.namesLabel, y, rh, H_NAMES_PREV, H_NAMES_NEXT, active);
        y += rh;
        toggleRow(c, p, "Haptic feedback", u.hapticsT, y, rh, H_HAPTIC, active);
        y += rh;
        toggleRow(c, p, "Keep screen on", u.keepOnT, y, rh, H_KEEPON, active);

        c.restore();
        hitDy = saveDy;
        clipT = Float.NEGATIVE_INFINITY;
        clipB = Float.POSITIVE_INFINITY;

        if (u.settingsMaxScroll > 0f) {
            float trackH = viewBottom - viewTop - 32f * dp;
            float thumbH = Math.max(24f * dp, trackH * (viewBottom - viewTop) / (contentBottom - viewTop));
            float ty = viewTop + 16f * dp + (trackH - thumbH) * (scroll / u.settingsMaxScroll);
            float tx = left + usable - 6f * dp;
            stroke.setStrokeWidth(2f * dp);
            stroke.setColor(col(p.line));
            c.drawLine(tx, ty, tx, ty + thumbH, stroke);
        }
    }

    private float controlY(float y, float rh) {
        float lab = 18f * sp;
        return y + lab + (rh - lab) / 2f;
    }

    private void rowLabel(Canvas c, Palette p, String label, float y) {
        text.setTypeface(regular);
        text.setTextSize(13f * sp);
        text.setColor(col(p.fg, 0.9f));
        fitLine(c, label, cx0, y + 14f * sp, usable - 32f * dp);
    }

    private void selectorRow(Canvas c, Palette p, String label, String value, float y, float rh,
                             int prevId, int nextId, boolean active) {
        rowLabel(c, p, label, y);
        float cx = cx0, cy = controlY(y, rh);
        text.setTypeface(light);
        text.setTextSize(17f * sp);
        text.setColor(col(p.fg));
        float tw = text.measureText(value);
        textCentered(c, value, cx, cy);
        float off = Math.min(Math.max(tw, 96f * dp) / 2f + 18f * dp, usable / 2f - 24f * dp);
        chevron(c, cx - off, cy, -1, 5f * dp, 1.5f * dp, col(p.dim));
        chevron(c, cx + off, cy, 1, 5f * dp, 1.5f * dp, col(p.dim));
        if (active) {
            float hh = Math.min(26f * dp, rh / 2f);
            hit(prevId, cx - off - 32f * dp, cy - hh, cx - 1f, cy + hh, cx - off, cy, 20f * dp);
            hit(nextId, cx + 1f, cy - hh, cx + off + 32f * dp, cy + hh, cx + off, cy, 20f * dp);
        }
    }

    private void toggleRow(Canvas c, Palette p, String label, float t, float y, float rh, int id, boolean active) {
        rowLabel(c, p, label, y);
        float cx = cx0, cy = controlY(y, rh);
        float r = clamp((rh - 18f * sp) / 2f - 6f * dp, 14f * dp, 22f * dp);
        if (t > 0.001f) {
            fill.setColor(col(p.accent, t));
            c.drawCircle(cx, cy, r, fill);
        }
        if (t < 0.999f) {
            stroke.setStrokeWidth(1f * dp);
            stroke.setColor(col(p.dim, 1f - t));
            c.drawCircle(cx, cy, r, stroke);
        }
        text.setTypeface(regular);
        text.setTextSize(Math.min(11f * sp, r * 0.62f));
        if (t > 0.001f) {
            text.setColor(col(p.onAccentText, t));
            textCentered(c, "ON", cx, cy);
        }
        if (t < 0.999f) {
            text.setColor(col(p.dim, 1f - t));
            textCentered(c, "OFF", cx, cy);
        }
        float hr = Math.max(r + 6f * dp, 24f * dp);
        if (active) hit(id, cx - hr - 12f * dp, cy - hr, cx + hr + 12f * dp, cy + hr, cx, cy, r + 6f * dp);
    }

    // ---- guided page -------------------------------------------------------------------------

    /** Row of the guide's own buttons, above the bottom bar. */
    private float guidePillY() {
        return barCy - (tight ? 48f : 58f) * dp;
    }

    private void drawGuidePage(Canvas c, Palette p, boolean active) {
        float top = headerCy + (tight ? 22f : 30f) * dp;
        int n = u.stringCount;
        float minRow = 30f * dp, maxRow = 46f * dp;
        // What fits, given up in this order as the window shortens: the strings' line diagram
        // (first a row of circles, then nothing; or, wide enough, it sits beside the words), the
        // sub-line, the meter's labels and second line, the caption, the advice line, the title, the
        // Skip / Auto row. Nothing is drawn over anything else, and nothing given up comes back
        // while the height only shrinks.
        pillsShown = true;
        captionShown = true;
        titleShown = true;
        meterCompact = false;
        meterDetail = true;
        float gap = 18f * dp;
        float wideW = Math.min(usable - 40f * dp, 400f * dp);
        boolean side = usable >= 560f * dp
                && guideBottom() - top - guideTextH(wideW, true) - guideMeterH() - 2f * gap < n * minRow;
        float colW = side ? usable / 2f : usable;
        float textW = side ? Math.min(colW - 32f * dp, 400f * dp) : wideW;
        float meterW = side ? Math.min(colW - 32f * dp, 320f * dp) : Math.min(usable - 48f * dp, 340f * dp);
        float H = guideBottom() - top;
        float rowsH = H;                  // before the Skip / Auto row can be given up and hand back room
        float bare = guideTextH(textW, false);
        // Skip / Next and Auto switch go last, in windows under ~260 dp tall; tapping the meter
        // then does what Next did (TunerView).
        int steps = 0;
        for (; steps < 5 && bare + gap + guideMeterH() > H; steps++) {
            switch (steps) {
                case 0: meterCompact = true; gap = 12f * dp; break;
                case 1: captionShown = false; break;
                case 2: meterDetail = false; break;
                case 3: titleShown = false; break;
                default: pillsShown = false; break;
            }
            H = guideBottom() - top;
            bare = guideTextH(textW, false);
        }
        // Past the first step the strings and the sub-line are long gone: shrinking the meter must
        // not bring them back, or the page flickers while the window shortens.
        boolean trimmed = steps > 0;
        H = Math.max(1f, H);
        float meterH = guideMeterH();
        float full = guideTextH(textW, true);

        if (!side) {
            float dW = Math.min(usable - 40f * dp, 420f * dp);
            float cr = clamp(dW / n / 2f - 6f * dp, 12f * dp, 18f * dp), compactH = 2f * cr + 12f * dp;
            float spare = H - full - meterH - 2f * gap;          // left for the strings with the sub-line kept
            boolean diag = !trimmed && spare >= n * minRow;
            boolean compact = !trimmed && !diag && spare >= compactH;
            boolean showSub = !trimmed && (diag || compact || H - full - meterH - gap >= 0f);
            float textH = showSub ? full : bare;
            float blockH = diag ? n * clamp(spare / n, minRow, maxRow) : compact ? compactH : 0f;
            float rowH = diag ? blockH / n : 0f;
            boolean strings = diag || compact;
            float used = textH + (strings ? gap + blockH : 0f) + gap + meterH;
            float g = gap + Math.min(Math.max(0f, H - used) / 3f, 26f * dp);
            float total = used + (strings ? 2f : 1f) * (g - gap);
            float y = top + Math.max(0f, (H - total) / 2f);
            y = drawGuideText(c, p, cx0, y, textW, showSub);
            if (diag) {
                drawGuideStrings(c, p, cx0 - dW / 2f, cx0 + dW / 2f, y + g, rowH, active);
                y += g + blockH;
            } else if (compact) {
                drawGuideCircles(c, p, cx0, y + g + compactH / 2f, dW, cr, active);
                y += g + blockH;
            }
            drawGuideMeter(c, p, cx0, y + g, meterW, meterH, usable - 24f * dp, active);
        } else {
            // wide: strings on the left, words and meter on the right
            float rowH = Math.min(maxRow, H / n);
            float sideW = colW - 40f * dp;
            if (Math.min(maxRow, rowsH / n) >= 18f * dp) {
                drawGuideStrings(c, p, left + 24f * dp, left + 24f * dp + sideW, top + Math.max(0f, (H - n * rowH) / 2f), rowH, active);
            }
            float rcx = left + colW * 1.5f;
            boolean sub = !trimmed && full + gap + meterH <= H;
            float th = sub ? full : bare;
            float y = top + Math.max(0f, (H - th - gap - meterH) / 2f);
            y = drawGuideText(c, p, rcx, y, textW, sub);
            drawGuideMeter(c, p, rcx, y + gap, meterW, meterH, colW - 24f * dp, active);
        }
        u.gPills = pillsShown;
        if (pillsShown) drawGuidePills(c, p, active);
    }

    /** Bottom of the guide's content: above its own buttons, or above the bottom bar without them. */
    private float guideBottom() {
        return pillsShown ? guidePillY() - (tight ? 30f : 36f) * dp : barCy - (tight ? 30f : 36f) * dp;
    }

    private float guideMeterH() {
        if (meterCompact) return 22f * dp + 26f * sp * 1.2f + (meterDetail ? 14f * sp * 1.4f : 0f);
        return 46f * dp + 30f * sp * 1.25f + 14f * sp * 1.4f * 2f;
    }

    private float guideTextH(float w, boolean sub) {
        text.setTypeface(light);
        text.setTextSize(26f * sp);
        float h = (captionShown ? 22f * dp : 0f) + (titleShown ? lines(u.gTitle, w) * 26f * sp * 1.2f + 6f * dp : 0f);
        if (sub && titleShown && u.gSub.length() > 0) {
            text.setTypeface(regular);
            text.setTextSize(14f * sp);
            h += lines(u.gSub, w) * 14f * sp * 1.4f;
        }
        return h;
    }

    /** Caption, title and sub-line; the title and sub-line slide in when the step changes. */
    private float drawGuideText(Canvas c, Palette p, float cx, float y, float w, boolean sub) {
        if (captionShown) {
            text.setTypeface(regular);
            text.setTextSize(12f * sp);
            text.setColor(col(p.dim));
            fitLine(c, u.gCaption, cx, y + 15f * sp, w);
            y += 22f * dp;
        }
        if (!titleShown) return y;
        text.setTypeface(light);
        text.setTextSize(26f * sp);
        float lh = 26f * sp * 1.2f;
        // as tall as the title will be once it has settled: the layout does not jump mid-fade
        float th = lines(u.gTitle, w) * lh;
        fade(c, u.gTitlePrev, u.gTitle, u.gTitleT, cx, y + th / 2f, w, lh, p.fg);
        y += th + 6f * dp;
        if (sub && u.gSub.length() > 0) {
            text.setTypeface(regular);
            text.setTextSize(14f * sp);
            float sl = 14f * sp * 1.4f;
            float sh = lines(u.gSub, w) * sl;
            fade(c, u.gSubPrev, u.gSub, u.gTitleT, cx, y + sh / 2f, w, sl, p.dim);
            y += sh;
        }
        return y;
    }

    /** Old text leaves upward while the new one arrives from below. */
    private void fade(Canvas c, String prev, String cur, float t, float cx, float midY, float w, float lineH, int color) {
        float base = a, e = prev.equals(cur) ? 1f : smooth(t), d = 8f * dp;
        if (e < 1f && prev.length() > 0) {
            a = base * (1f - e);
            text.setColor(col(color));
            wrapped(c, prev, cx, midY - d * e, w, lineH);
        }
        a = base * e;
        text.setColor(col(color));
        wrapped(c, cur, cx, midY + d * (1f - e), w, lineH);
        a = base;
    }

    /**
     * The strings as seen when holding the guitar: top row nearest the face, thicker for lower
     * pitch. The one being tuned is highlighted and moves: a demonstration pluck while waiting,
     * vibration while it sounds, and a sag while it is far too slack.
     */
    private void drawGuideStrings(Canvas c, Palette p, float x0, float x1, float yTop, float rowH, boolean active) {
        int n = u.stringCount;
        float r = Math.min(13f * dp, rowH * 0.34f);
        float cxC = x0 + r + 2f * dp;
        float lx0 = cxC + r + 12f * dp, lx1 = x1;
        if (!u.guideDone) {
            float by = yTop + (u.gCursor + 0.5f) * rowH, inset = Math.min(3f * dp, rowH * 0.08f);
            fill.setColor(col(p.accent, Palette.ROW_TINT));
            rect.set(x0 - 10f * dp, by - rowH / 2f + inset, x1 + 10f * dp, by + rowH / 2f - inset);
            c.drawRoundRect(rect, rowH / 2f - inset, rowH / 2f - inset, fill);
        }
        float tsz = Math.min(14f * sp, r * 1.0f);
        for (int i = 0; i < n; i++) {
            float y = yTop + (i + 0.5f) * rowH;
            float k = u.guideDone ? 0f : clamp(1f - Math.abs(i - u.gCursor), 0f, 1f);
            float tt = u.sTunedT[i];
            float th = (0.9f + 2.4f * u.gThick[i]) * dp;
            int lineCol = Palette.mix(Palette.mix(p.line, p.dim, 0.5f), p.accentStroke, k);
            stroke.setStrokeWidth(th);
            stroke.setColor(col(lineCol));
            if (i == u.gStep && !u.guideDone) liveString(c, p, lx0, lx1, y, rowH, i, n);
            else c.drawLine(lx0, y, lx1, y, stroke);

            stringCircle(c, p, cxC, y, r, tt, k, 1f);
            if (tt < 0.999f) letter(c, u.sLetter[i], u.sAcc[i], cxC, y, tsz, col(Palette.mix(p.fg, p.accentText, k), 1f - tt));
            if (tt > 0.001f) check(c, cxC, y, r * 0.48f, tt, col(p.onAccentText));
            if (active) hit(H_GSTRING + i, x0 - 10f * dp, y - rowH / 2f, x1 + 10f * dp, y + rowH / 2f, cxC, y, r + 5f * dp);
        }
    }

    /** Strings as one row of circles, top string first, for windows too short for the diagram. */
    private void drawGuideCircles(Canvas c, Palette p, float cx, float cy, float maxW, float r, boolean active) {
        int n = u.stringCount;
        float s = Math.min(2f * r + 14f * dp, maxW / n);
        float tsz = Math.min(14f * sp, r * 0.9f);
        for (int i = 0; i < n; i++) {
            float x = cx + (i - (n - 1) / 2f) * s;
            float k = u.guideDone ? 0f : clamp(1f - Math.abs(i - u.gCursor), 0f, 1f);
            float tt = u.sTunedT[i];
            stringCircle(c, p, x, cy, r, tt, k, 1f);
            if (tt < 0.999f) letter(c, u.sLetter[i], u.sAcc[i], x, cy, tsz, col(Palette.mix(p.fg, p.accentText, k), 1f - tt));
            if (tt > 0.001f) check(c, x, cy, r * 0.48f, tt, col(p.onAccentText));
            if (i == u.gStep && !u.guideDone) {
                // waiting: a ring leaves the circle with each demonstration pluck; sounding: it breathes
                float q = u.gPluckT >= 0.7f ? (u.gPluckT - 0.7f) / 0.8f : -1f;
                if (q >= 0f && q <= 1f) {
                    stroke.setStrokeWidth(1.5f * dp);
                    stroke.setColor(col(p.accent, 0.6f * (1f - q)));
                    c.drawCircle(x, cy, r + 2f * dp + q * 10f * dp, stroke);
                } else if (u.gVib > 0.01f) {
                    float b = (float) (0.5 + 0.5 * Math.sin(u.time * 2.0 * Math.PI * 4.0));
                    stroke.setStrokeWidth(1.25f * dp);
                    stroke.setColor(col(p.accent, 0.5f * u.gVib));
                    c.drawCircle(x, cy, r + 3f * dp + b * 1.5f * dp * u.gVib, stroke);
                }
            }
            if (active) hit(H_GSTRING + i, x - s / 2f, cy - r - 8f * dp, x + s / 2f, cy + r + 8f * dp, x, cy, r + 5f * dp);
        }
    }

    private void liveString(Canvas c, Palette p, float lx0, float lx1, float y, float rowH, int i, int n) {
        float L = lx1 - lx0, t = u.time;
        float sag = u.gSag * Math.min(12f * dp, rowH * 0.3f);
        float vib = u.gVib * Math.min(3.5f * dp, rowH * 0.09f);
        float fv = 5.5f + 5f * (n > 1 ? i / (n - 1f) : 0f);           // visual wobble, Hz
        float xpF = 0.7f, ph = u.gPluckT;
        float pull = 0f, ring = 0f;
        if (ph >= 0f) {
            if (ph >= 0.3f && ph < 0.7f) pull = smooth((ph - 0.3f) / 0.4f);
            else if (ph >= 0.7f) {
                float rt = ph - 0.7f;
                ring = (float) (Math.exp(-rt * 2.4) * Math.cos(2.0 * Math.PI * 7.0 * rt));
            }
        }
        float pullPx = pull * Math.min(8f * dp, rowH * 0.22f);
        float ringPx = ring * Math.min(6f * dp, rowH * 0.15f);
        path.reset();
        int segs = 48;
        for (int s = 0; s <= segs; s++) {
            float q = s / (float) segs, x = lx0 + L * q;
            double sn = Math.sin(Math.PI * q);
            double yy = y + sag * sn + ringPx * sn
                    + vib * (sn * Math.sin(2.0 * Math.PI * fv * t)
                    + 0.35 * Math.sin(2.0 * Math.PI * q) * Math.sin(2.0 * Math.PI * 2.0 * fv * t + 1.3));
            if (pullPx > 0f) yy += pullPx * (q < xpF ? q / xpF : (1f - q) / (1f - xpF));
            if (s == 0) path.moveTo(x, (float) yy);
            else path.lineTo(x, (float) yy);
        }
        c.drawPath(path, stroke);

        if (ph >= 0f && ph < 1.1f) {
            // the pick: comes down, pushes the string, slips past
            float xp = lx0 + L * xpF, dr = 5f * dp, sw = stroke.getStrokeWidth();
            float dy, al;
            if (ph < 0.3f) {
                float e = smooth(ph / 0.3f);
                dy = y - dr - sw / 2f - (1f - e) * 16f * dp;
                al = e;
            } else if (ph < 0.7f) {
                dy = y - dr - sw / 2f + pullPx;
                al = 1f;
            } else {
                float e = (ph - 0.7f) / 0.4f;
                dy = y + dr + 8f * dp * smooth(e) + Math.min(8f * dp, rowH * 0.22f);
                al = 1f - e;
            }
            fill.setColor(col(p.accent, 0.9f * al));
            c.drawCircle(xp, dy, dr, fill);
            if (ph >= 0.7f) {
                float q = (ph - 0.7f) / 0.4f;
                stroke.setStrokeWidth(1.5f * dp);
                stroke.setColor(col(p.accent, 0.5f * (1f - q)));
                c.drawCircle(xp, y, 4f * dp + q * 20f * dp, stroke);
            }
        }
    }

    private void drawGuideMeter(Canvas c, Palette p, float cx, float y0, float w, float h, float textMax, boolean active) {
        if (u.message.length() > 0) {
            drawMessage(c, p, cx, y0 + h / 2f, w, h, active);
            return;
        }
        if (active) hit(H_GUIDE, cx - w / 2f, y0, cx + w / 2f, y0 + h, 0, 0, -1f);
        if (u.guideDone) {
            float s = Math.min(1f, u.gDoneT / 0.6f);
            float R = 30f * dp * backOut(s);
            float my = y0 + h * 0.42f;
            if (R > 0.5f) {
                fill.setColor(col(p.accent));
                c.drawCircle(cx, my, R, fill);
            }
            check(c, cx, my, 30f * dp * 0.45f, (u.gDoneT - 0.35f) / 0.65f, col(p.onAccentText));
            return;
        }
        float my = y0 + (meterCompact ? 12f : 18f) * dp, x0 = cx - w / 2f, x1 = cx + w / 2f;
        float tr = 13f * dp, br = 8f * dp, tune = u.tuneT;

        stroke.setStrokeWidth(1.25f * dp);
        stroke.setColor(col(p.line));
        c.drawLine(x0, my, cx - tr - 5f * dp, my, stroke);
        c.drawLine(cx + tr + 5f * dp, my, x1, my, stroke);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(p.dim, 0.8f));
        c.drawLine(x0, my - 5f * dp, x0, my + 5f * dp, stroke);
        c.drawLine(x1, my - 5f * dp, x1, my + 5f * dp, stroke);
        if (!meterCompact) {
            text.setTypeface(regular);
            text.setTextSize(12f * sp);
            text.setColor(col(p.dim));
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("Too low", x0, my + 24f * dp, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Too high", x1, my + 24f * dp, text);
            text.setTextAlign(Paint.Align.CENTER);
        }

        float bx = cx + u.bead * (w / 2f - br - 2f * dp);
        if (u.gChevT > 0.001f && u.noteT > 0.001f) {
            // chevrons march from the bead toward the target: which way to go, and how far
            int dir = bx < cx ? 1 : -1;
            float from = bx + dir * (br + 9f * dp), span = (cx - dir * (tr + 9f * dp) - from) * dir;
            float step = 13f * dp, speed = (16f + 34f * Math.abs(u.bead)) * dp;
            float off = (u.time * speed) % step;
            for (float d = off; d < span; d += step) {
                float fadeIn = Math.min(1f, d / (8f * dp)), fadeOut = Math.min(1f, (span - d) / (8f * dp));
                chevron(c, from + dir * d, my, dir, 4f * dp, 1.5f * dp, col(p.accentStroke, Palette.CHEVRON_ALPHA * u.gChevT * u.noteT * fadeIn * fadeOut));
            }
        }
        if (u.noteT > 0.001f) bead(c, p, bx, my, br, u.noteT, u.liveT);
        if (tune > 0.001f) {
            fill.setColor(col(p.accent, tune));
            c.drawCircle(cx, my, tr, fill);
        }
        targetRing(c, p, cx, my, tr, tune, 22f * dp);
        if (tune > 0.001f) check(c, cx, my, tr * 0.48f, tune, col(p.onAccentText));

        // what to do, in one word, and why
        float shake = u.gShakeT >= 0f ? 7f * dp * (float) Math.sin(u.gShakeT * 6.0 * Math.PI) * (1f - u.gShakeT) : 0f;
        float ws = (meterCompact ? 26f : 30f) * sp;
        float wy = my + (meterCompact ? 20f : 42f) * dp + ws * 0.55f;
        text.setTypeface(light);
        text.setTextSize(ws);
        // one line, always: the meter's height has room for one
        fade(c, u.gWordPrev, u.gWord, u.gWordT, cx + shake, wy, textMax, 1e6f, u.gWordAccent ? p.accentText : p.fg);
        text.setTypeface(regular);
        text.setTextSize(14f * sp);
        float dl = 14f * sp * 1.4f;
        if (meterCompact) {
            if (!meterDetail) return;
            // one line: the first sentence of the advice, shrunk to fit its column if it must
            String d = u.gDetail;
            if (text.measureText(d) > textMax) d = firstSentence(d);
            text.setColor(col(p.dim));
            fitLine(c, d, cx, wy + ws * 0.6f + dl * 0.75f, textMax);
        } else {
            fade(c, u.gDetailPrev, u.gDetail, u.gWordT, cx, wy + ws * 0.6f + dl, w + 24f * dp, dl, p.dim);
        }
    }

    private void drawGuidePills(Canvas c, Palette p, boolean active) {
        float y = guidePillY(), h = (tight ? 36f : 40f) * dp;
        if (u.guideDone) {
            pill(c, p, "Check again", cx0, y, H_GUIDE_AGAIN, active, false, Math.min(150f * dp, usable - 48f * dp), h);
        } else {
            // Skip (or Next, when strings switch by hand) and the switching mode itself
            float off = Math.min(70f * dp, usable / 4f), mw = Math.min(128f * dp, usable / 2f - 12f * dp);
            pill(c, p, u.gAuto ? "Skip" : "Next", cx0 - off, y, H_GUIDE_SKIP, active, false, mw, h);
            pill(c, p, "Auto switch", cx0 + off, y, H_GUIDE_AUTO, active, u.gAuto, mw, h);
        }
    }

    /** Check mark drawn up to {@code t} (0..1) of its length, centred at (cx, cy), half-size s. */
    private void check(Canvas c, float cx, float cy, float s, float t, int color) {
        t = clamp(t, 0f, 1f);
        if (t <= 0f) return;
        float ax = cx - 0.9f * s, ay = cy + 0.05f * s, bx = cx - 0.3f * s, by = cy + 0.62f * s;
        float ex = cx + 0.95f * s, ey = cy - 0.6f * s;
        float l1 = (float) Math.hypot(bx - ax, by - ay), l2 = (float) Math.hypot(ex - bx, ey - by);
        float len = (l1 + l2) * t;
        stroke.setStrokeWidth(Math.max(1.5f * dp, s * 0.24f));
        stroke.setColor(color);
        if (len <= l1) {
            float f = len / l1;
            c.drawLine(ax, ay, ax + (bx - ax) * f, ay + (by - ay) * f, stroke);
        } else {
            float f = (len - l1) / l2;
            c.drawLine(ax, ay, bx, by, stroke);
            c.drawLine(bx, by, bx + (ex - bx) * f, by + (ey - by) * f, stroke);
        }
    }

    /** Note letter with a drawn accidental, centred at (x, cy). */
    private void letter(Canvas c, char letter, int acc, float x, float cy, float tsz, int color) {
        text.setTypeface(regular);
        text.setTextSize(tsz);
        text.setColor(color);
        String L = glyph(letter);
        float lw = text.measureText(L);
        float cap = tsz * 0.70f, ah = cap * 0.62f, aw = acc == 0 ? 0f : (acc == 1 ? ah * 0.62f : ah * 0.45f) + tsz * 0.06f;
        float lx = x - aw / 2f, baseY = cy + cap / 2f;
        c.drawText(L, lx, baseY, text);
        if (acc != 0) accidental(c, acc, lx + lw / 2f + tsz * 0.06f, baseY - cap, ah, Math.max(1f * dp, tsz * 0.07f), color);
    }

    /** A string's circle: filled accent once tuned, else an outline that turns accent as it becomes the current one (k). */
    private void stringCircle(Canvas c, Palette p, float x, float y, float r, float tuned, float k, float grow) {
        if (tuned > 0.001f) {
            fill.setColor(col(p.accent, tuned));
            c.drawCircle(x, y, r, fill);
        }
        if (tuned < 0.999f) {
            stroke.setStrokeWidth((1f + grow * k) * dp);
            stroke.setColor(col(Palette.mix(Palette.alpha(p.dim, 0.7f), p.accentStroke, k), 1f - tuned));
            c.drawCircle(x, y, r, stroke);
        }
    }

    /** The needle's bead: bg-coloured so it hides what it rides over, filled accent while the pitch is live, hollow when held. */
    private void bead(Canvas c, Palette p, float x, float y, float r, float noteT, float live) {
        fill.setColor(col(p.bg, noteT));
        c.drawCircle(x, y, r, fill);
        if (live > 0.001f) {
            fill.setColor(col(p.accent, noteT * live));
            c.drawCircle(x, y, r, fill);
        }
        stroke.setStrokeWidth(1.25f * dp);
        stroke.setColor(col(Palette.mix(p.dim, p.accentStroke, live), noteT));
        c.drawCircle(x, y, r, stroke);
    }

    /** The target ring, turning accent and thicker as the pitch settles; a pulse leaves it on reaching pitch. */
    private void targetRing(Canvas c, Palette p, float x, float y, float r, float tune, float pulseGrow) {
        stroke.setStrokeWidth((1f + 1.25f * tune) * dp);
        stroke.setColor(col(Palette.mix(p.dim, p.accentStroke, tune)));
        c.drawCircle(x, y, r, stroke);
        if (u.pulseT >= 0f) {
            float q = u.pulseT;
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(col(p.accent, (1f - q) * 0.8f));
            c.drawCircle(x, y, r + q * pulseGrow, stroke);
        }
    }

    /** Note and cents colour: dim until a pitch is live, fg while it is, accent text once in tune. */
    private static int readoutColor(Palette p, float live, float tune) {
        return Palette.mix(Palette.mix(p.dim, p.fg, 0.45f + 0.55f * live), p.accentText, tune);
    }

    private static String glyph(char ch) {
        return ch < ASCII.length ? ASCII[ch] : String.valueOf(ch);
    }

    private static String strumLabel(int cents) {
        if (cents < -STRUM_MAX || cents > STRUM_MAX) return (cents < 0 ? "−" : "+") + Math.abs(cents);
        String s = STRUM_LABELS[cents + STRUM_MAX];
        if (s == null) s = STRUM_LABELS[cents + STRUM_MAX] = (cents < 0 ? "−" : "+") + Math.abs(cents);
        return s;
    }

    /** The advice up to its first full stop, for the one line a compact meter has. */
    private String firstSentence(String s) {
        if (s != sentenceSrc && !s.equals(sentenceSrc)) {
            sentenceSrc = s;
            int dot = s.indexOf(". ");
            sentence = dot > 0 ? s.substring(0, dot + 1) : s;
        }
        return sentence;
    }

    private static float smooth(float t) {
        t = t < 0f ? 0f : t > 1f ? 1f : t;
        return t * t * (3f - 2f * t);
    }

    private static float backOut(float t) {
        float k = 1.70158f, q = t - 1f;
        return 1f + (k + 1f) * q * q * q + k * q * q;
    }

    // ---- primitives --------------------------------------------------------------------------

    private void pill(Canvas c, Palette p, String label, float cx, float cy, int id, boolean active,
                      boolean filled, float maxW, float h) {
        text.setTypeface(regular);
        text.setTextSize(Math.min(14f * sp, h * 0.4f));
        float w = Math.min(Math.max(112f * dp, text.measureText(label) + 48f * dp), Math.max(maxW, 72f * dp));
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        if (filled) {
            fill.setColor(col(p.accent));
            c.drawRoundRect(rect, h / 2f, h / 2f, fill);
            text.setColor(col(p.onAccentText));
        } else {
            stroke.setStrokeWidth(1f * dp);
            stroke.setColor(col(p.dim, 0.7f));
            c.drawRoundRect(rect, h / 2f, h / 2f, stroke);
            text.setColor(col(p.fg));
        }
        float size = text.getTextSize(), tw = text.measureText(label);
        if (tw > w - 24f * dp && tw > 0f) text.setTextSize(size * (w - 24f * dp) / tw);
        textCentered(c, label, cx, cy);
        text.setTextSize(size);
        float ext = Math.max(6f * dp, (48f * dp - h) / 2f);                // touch target >= 48 dp
        if (active && hit(id, rect.left - 4f * dp, rect.top - ext, rect.right + 4f * dp, rect.bottom + ext, cx, cy, 0f)) {
            Ui.Hit hh = u.hits[u.hitCount - 1];
            hh.pw = w / 2f;
            hh.ph = h / 2f;
        }
    }

    private void drawPressed(Canvas c, Palette p) {
        if (u.focusId != 0) {
            // keyboard focus: an accent outline just outside the press shape
            Ui.Hit f = u.find(u.focusId);
            if (f != null) {
                float g = 3f * dp;
                stroke.setStrokeWidth(2f * dp);
                stroke.setColor(p.accentStroke);
                if (f.vr > 0f) {
                    c.drawCircle(f.vx, f.vy, f.vr + g, stroke);
                } else if (f.vr == 0f) {
                    rect.set(f.vx - f.pw - g, f.vy - f.ph - g, f.vx + f.pw + g, f.vy + f.ph + g);
                    c.drawRoundRect(rect, f.ph + g, f.ph + g, stroke);
                } else {
                    rect.set(f.rect.left + g, f.rect.top + g, f.rect.right - g, f.rect.bottom - g);
                    c.drawRoundRect(rect, 10f * dp, 10f * dp, stroke);
                }
            }
        }
        if (u.pressedId == 0) return;
        Ui.Hit h = u.find(u.pressedId);
        if (h == null || h.vr < 0f) return;
        fill.setColor(Palette.alpha(p.fg, 0.08f));
        if (h.vr > 0f) {
            c.drawCircle(h.vx, h.vy, h.vr, fill);
        } else {
            rect.set(h.vx - h.pw, h.vy - h.ph, h.vx + h.pw, h.vy + h.ph);
            c.drawRoundRect(rect, h.ph, h.ph, fill);
        }
    }

    private void chevron(Canvas c, float x, float y, int dir, float size, float w, int color) {
        stroke.setStrokeWidth(w);
        stroke.setColor(color);
        float tip = x + dir * size * 0.5f, back = x - dir * size * 0.5f;
        path.reset();
        path.moveTo(back, y - size);
        path.lineTo(tip, y);
        path.lineTo(back, y + size);
        c.drawPath(path, stroke);
    }

    private void iconSliders(Canvas c, float x, float y, int color, int bg) {
        float s = dp;
        stroke.setStrokeWidth(1.5f * s);
        stroke.setColor(color);
        c.drawLine(x - 10f * s, y - 4.5f * s, x + 10f * s, y - 4.5f * s, stroke);
        c.drawLine(x - 10f * s, y + 4.5f * s, x + 10f * s, y + 4.5f * s, stroke);
        fill.setColor(bg);
        c.drawCircle(x - 3.5f * s, y - 4.5f * s, 3f * s, fill);
        c.drawCircle(x + 4f * s, y + 4.5f * s, 3f * s, fill);
        c.drawCircle(x - 3.5f * s, y - 4.5f * s, 3f * s, stroke);
        c.drawCircle(x + 4f * s, y + 4.5f * s, 3f * s, stroke);
    }

    /** Advanced tuner: the dial in miniature, a ring with its bead off-centre. Filled while on. */
    private void iconDial(Canvas c, Palette p, float x, float y, boolean on) {
        float s = dp;
        int color;
        int hole;
        if (on) {
            fill.setColor(col(p.accent));
            c.drawCircle(x, y, 16f * s, fill);
            color = col(p.onAccentText);
            hole = col(p.accent);
        } else {
            color = col(p.dim);
            hole = col(p.bg);
        }
        stroke.setStrokeWidth(1.5f * s);
        stroke.setColor(color);
        c.drawCircle(x, y + 1f * s, 8.5f * s, stroke);
        double ang = Math.toRadians(-60.0);
        float bx = x + 8.5f * s * (float) Math.cos(ang), by = y + 1f * s + 8.5f * s * (float) Math.sin(ang);
        fill.setColor(hole);
        c.drawCircle(bx, by, 3.2f * s, fill);
        fill.setColor(color);
        c.drawCircle(bx, by, 2.4f * s, fill);
        c.drawLine(x, y + 1f * s - 3.5f * s, x, y + 1f * s + 0.5f * s, stroke);   // centre tick: the target
    }

    /** Tuning fork: the reference tone. Filled accent disc while it sounds. */
    private void iconFork(Canvas c, Palette p, float x, float y, boolean on) {
        float s = dp;
        if (on) {
            fill.setColor(col(p.accent));
            c.drawCircle(x, y, 16f * s, fill);
            stroke.setColor(col(p.onAccentText));
        } else {
            stroke.setColor(col(p.dim));
        }
        stroke.setStrokeWidth(1.5f * s);
        path.reset();
        path.moveTo(x - 3.5f * s, y - 10.5f * s);
        path.lineTo(x - 3.5f * s, y - 1f * s);
        path.cubicTo(x - 3.5f * s, y + 3.7f * s, x + 3.5f * s, y + 3.7f * s, x + 3.5f * s, y - 1f * s);
        path.lineTo(x + 3.5f * s, y - 10.5f * s);
        c.drawPath(path, stroke);
        c.drawLine(x, y + 2.6f * s, x, y + 10.5f * s, stroke);
    }

    /** Pause: listening stops and the microphone is released. Filled accent disc while paused. */
    private void iconPause(Canvas c, Palette p, float x, float y, boolean paused) {
        float s = dp;
        if (paused) {
            fill.setColor(col(p.accent));
            c.drawCircle(x, y, 16f * s, fill);
            stroke.setColor(col(p.onAccentText));
        } else {
            stroke.setColor(col(p.dim));
        }
        stroke.setStrokeWidth(1.75f * s);
        c.drawLine(x - 3.5f * s, y - 6.5f * s, x - 3.5f * s, y + 6.5f * s, stroke);
        c.drawLine(x + 3.5f * s, y - 6.5f * s, x + 3.5f * s, y + 6.5f * s, stroke);
    }

    private void textCentered(Canvas c, String s, float cx, float cy) {
        text.getFontMetrics(fm);
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    /** Centred text, wrapped onto at most two lines at word boundaries, the second shrunk to fit. */
    private void wrapped(Canvas c, String s, float cx, float midY, float maxW, float lineH) {
        if (s == null || s.length() == 0) return;
        text.getFontMetrics(fm);
        float half = -(fm.ascent + fm.descent) / 2f;
        if (lineH > 1e5f) {
            fitLine(c, s, cx, midY + half, maxW);     // single line, shrunk to fit
            return;
        }
        Wrap k = wrap(s, maxW);
        if (k.one) {
            c.drawText(s, cx, midY + half, text);
        } else if (k.first == null) {
            fitLine(c, s, cx, midY + half, maxW);
        } else {
            c.drawText(k.first, cx, midY - lineH / 2f + half, text);
            fitLine(c, k.second, cx, midY + lineH / 2f + half, maxW);
        }
    }

    /** Where {@code s} breaks at the paint's current font and size; remembered, since text rarely changes between frames. */
    private Wrap wrap(String s, float maxW) {
        Typeface tf = text.getTypeface();
        float size = text.getTextSize();
        for (Wrap k : wraps) {
            if (k != null && k.maxW == maxW && k.size == size && k.tf == tf && (k.src == s || k.src.equals(s))) return k;
        }
        Wrap k = wraps[wrapNext];
        if (k == null) k = wraps[wrapNext] = new Wrap();
        wrapNext = (wrapNext + 1) % wraps.length;
        k.src = s;
        k.maxW = maxW;
        k.size = size;
        k.tf = tf;
        k.first = k.second = null;
        k.one = text.measureText(s) <= maxW;
        if (k.one) return k;
        int cut = -1;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            if (text.measureText(s, 0, i) <= maxW) cut = i;
            else break;
        }
        // prefer a sentence boundary when both halves fit: no stranded words
        for (int i = s.indexOf(". "); i > 0; i = s.indexOf(". ", i + 1)) {
            if (text.measureText(s, 0, i + 1) <= maxW && text.measureText(s, i + 2, s.length()) <= maxW) {
                cut = i + 1;
                break;
            }
        }
        if (cut >= 0) {
            k.first = s.substring(0, cut);
            k.second = s.substring(cut + 1);
        }
        return k;
    }

    private void fitLine(Canvas c, String s, float cx, float base, float maxW) {
        if (s == null || s.length() == 0) return;
        float size = text.getTextSize(), w = text.measureText(s);
        if (w > maxW && w > 0f) text.setTextSize(size * maxW / w);
        c.drawText(s, cx, base, text);
        text.setTextSize(size);
    }

    private boolean hit(int id, float l, float t, float r, float b, float vx, float vy, float vr) {
        t += hitDy;
        b += hitDy;
        vy += hitDy;
        if (t < clipT) t = clipT;
        if (b > clipB) b = clipB;
        if (b - t < 8f * dp) return false;           // scrolled out of view
        int before = u.hitCount;
        u.addHit(id, l, t, r, b, vx, vy, vr);
        return u.hitCount > before;
    }

    private int col(int c) {
        return Palette.alpha(c, a);
    }

    private int col(int c, float extra) {
        return Palette.alpha(c, a * extra);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
