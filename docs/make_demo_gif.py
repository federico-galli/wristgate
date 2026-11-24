#!/usr/bin/env python3
"""Renders docs/demo.gif: an illustration (not a screen recording) of what Wristgate does.

Story: group chat and boss messages stay on the phone; "Urgent, call me" from Mom gets through;
then Mom calls and the watch keeps buzzing.

    python3 -I docs/make_demo_gif.py [arch|bars] [output.gif]      (needs Pillow)

Gate styles: "night"  = airport-style arch in the app icon's colours: the gate IS the icon (default);
             "inside" = same arch, the app icon inside the passage decides;
             "arch"   = same arch, the app icon on top as a sign;
             "bars"   = garden gate whose bars lift.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
STYLE = sys.argv[1] if len(sys.argv) > 1 else "night"
NIGHT = STYLE == "night"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "demo.gif")
ICON = os.path.join(HERE, "icon.png")
FONT = "/usr/share/fonts/adwaita-sans-fonts/AdwaitaSans-Regular.ttf"  # variable font (Inter-based)
FONT_LED = "/usr/share/fonts/dejavu-sans-mono-fonts/DejaVuSansMono-Bold.ttf"

W, H = 960, 476          # drawing area (night style adds a title band on top)
TITLE_H = 58 if STYLE == "night" else 0
S = 2                    # supersampling for smooth edges
FPS = 15

BG = (238, 242, 248) if NIGHT else (242, 244, 246)
INK = (26, 29, 33)
MUTED = (94, 106, 117)
LINE = (198, 207, 222) if NIGHT else (205, 211, 217)
ACCENT = (31, 143, 92)
RED = (205, 70, 60)
CARD = (255, 255, 255)
HIGHLIGHT = (255, 226, 120)            # marker-pen yellow behind the keyword
AMBER = (255, 176, 46)

# app "dots": generic colors, no logos
GREEN, BLUE, PURPLE = (37, 180, 102), (52, 120, 246), (108, 70, 160)


def font(size, weight="Regular"):
    f = ImageFont.truetype(FONT, size * S)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


F_CAPTION = font(21, "SemiBold")
F_NAME = font(14, "SemiBold")
F_TEXT = font(13)
F_SMALL = font(12)
F_TIME = font(40, "SemiBold")
F_W_NAME = font(17, "SemiBold")
F_W_TEXT = font(14)
F_LABEL = font(14, "SemiBold")
F_TITLE = font(32, "Bold")
F_LED = ImageFont.truetype(FONT_LED, 19 * S)
F_GATE_NAME = font(17, "Bold")
F_TAG = font(12, "SemiBold")
KEYWORD = "Urgent"
PLATE_ICON = None   # small app icon for the name plate (loaded in main)

# ---------------------------------------------------------------- layout (1x coordinates)
BAND_TOP, BAND_BOTTOM = 46, 392        # phone, gate and watch share this vertical band
PHONE = (100, 76, 296, 376)           # x0, y0, x1, y1 (smaller: a supporting actor too;
                                      #  6 px below centre: optical centring, its content sits at the top)
GATE_HALF_W, GATE_TOP, GATE_BOTTOM = 70, 128, 318   # the drawn gate
GATE = (480, 220)                     # centre of the gate opening (where the route passes) = canvas centre,
                                      #  so title, gate and caption share one vertical axis
ARCH_HALF_W = 104
ARCH_TOP = 64 if STYLE in ("inside", "night") else BAND_TOP + 66   # the airport-style arch
# "inside": symmetric around the route line, like the phone and the watch (all centred on y = GATE[1])
ARCH_BOTTOM = 2 * 220 - ARCH_TOP if STYLE in ("inside", "night") else BAND_BOTTOM
ICON_INSIDE = 88                                    # app icon inside the passage ("inside" style)
ICON_SIGN = 66                                      # app icon on top of the arch
WATCH = (766, 220)                    # centre of the watch face  (equal gaps: phone|80|gate|80|watch)
WATCH_R = 90
STRAP = (186, 196, 216) if NIGHT else (176, 183, 190)   # light: the watch is a supporting actor, not the star
CASE = (118, 130, 156) if NIGHT else (132, 140, 148)
GATE_BODY = (30, 42, 70) if NIGHT else (44, 49, 55)   # night = the app icon's deep blue
DISPLAY = (8, 11, 20)
CARD_W, CARD_H = 172, 50
PHONE_BODY = (52, 62, 86) if NIGHT else (58, 64, 70)

# messages: (sender, text, dot colour, allowed?, kind)
EVENTS = [
    ("Family group", "lol, look at this", GREEN, False, "msg"),
    ("Boss", "Quick sync? 5 min", PURPLE, False, "msg"),
    ("Mom", "Urgent, call me", GREEN, True, "msg"),
    ("Mom", "Incoming call…", GREEN, True, "call"),
]
CAPTIONS = [
    "Group chats stay on your phone",
    "So does your boss",
    "Your keyword “urgent” gets through",
    "Calls keep buzzing until you answer",
]
SCENE = [2.6, 2.6, 3.4, 4.0]          # seconds per event
HOLD_END = 0.8


def s(v):
    return int(round(v * S))


def ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(int(lerp(a, b, t)) for a, b in zip(c1, c2))


# ---------------------------------------------------------------- drawing helpers
def rrect(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([s(box[0]), s(box[1]), s(box[2]), s(box[3])], radius=s(r),
                        fill=fill, outline=outline, width=s(width) if outline else 0)


def text(d, xy, t, f, fill, anchor="la"):
    d.text((s(xy[0]), s(xy[1])), t, font=f, fill=fill, anchor=anchor)


def dot(d, c, r, fill):
    d.ellipse([s(c[0] - r), s(c[1] - r), s(c[0] + r), s(c[1] + r)], fill=fill)


def handset(d, c, size, fill):
    """Tiny phone-handset glyph from two rounded bars (no emoji font needed)."""
    x, y = c
    k = size / 20
    d.line([s(x - 6 * k), s(y - 7 * k), s(x - 3 * k), s(y + 2 * k), s(x + 5 * k), s(y + 7 * k)],
           fill=fill, width=s(4 * k), joint="curve")
    dot(d, (x - 6 * k, y - 7 * k), 2.6 * k, fill)
    dot(d, (x + 6 * k, y + 7 * k), 2.6 * k, fill)


def highlight(d, xy, body, f, alpha=1.0, anchor="la"):
    """Marker-pen highlight behind KEYWORD inside body (text drawn at xy with the given anchor)."""
    full = d.textbbox((s(xy[0]), s(xy[1])), body, font=f, anchor=anchor)
    before = body[:body.index(KEYWORD)]
    x0 = full[0] + d.textlength(before, font=f)
    x1 = x0 + d.textlength(KEYWORD, font=f)
    d.rounded_rectangle([x0 - s(3), full[1] - s(2), x1 + s(3), full[3] + s(2)], radius=s(4),
                        fill=mix(BG, HIGHLIGHT, alpha))


def notification_card(d, x, y, sender, body, colour, alpha=1.0, call=False):
    fill = mix(BG, CARD, alpha)
    rrect(d, (x, y, x + CARD_W, y + CARD_H), 12, fill=fill)
    dot(d, (x + 16, y + 16), 5, mix(fill, colour, alpha))
    text(d, (x + 28, y + 8), sender, F_NAME, mix(fill, INK, alpha))
    if KEYWORD in body:
        highlight(d, (x + 28, y + 28), body, F_TEXT, alpha)
    text(d, (x + 28, y + 28), body, F_TEXT, mix(fill, MUTED, alpha))
    if call:
        handset(d, (x + CARD_W - 18, y + CARD_H / 2), 16, mix(fill, ACCENT, alpha))


def draw_phone(d, cards):
    x0, y0, x1, y1 = PHONE
    rrect(d, (x0, y0, x1, y1), 30, fill=PHONE_BODY)                # body (thin, dark grey bezel)
    rrect(d, (x0 + 6, y0 + 6, x1 - 6, y1 - 6), 25, fill=(232, 236, 240))  # screen
    rrect(d, ((x0 + x1) / 2 - 24, y0 + 12, (x0 + x1) / 2 + 24, y0 + 19), 4, fill=PHONE_BODY)  # speaker
    text(d, (x0 + 20, y0 + 24), "9:41", F_SMALL, MUTED)
    for (cx, cy, ev, alpha) in cards:
        sender, body, colour, _, kind = ev
        notification_card(d, cx, cy, sender, body, colour, alpha, call=(kind == "call"))


def draw_gate(d, icon, open_, green, red):
    """A real gate: two pillars, a top beam with the Wristgate icon as its sign, vertical bars.
    open_ 0..1 lifts the bars; green/red 0..1 tint the gate (let through / blocked)."""
    gx, gy = GATE
    left, right = gx - GATE_HALF_W, gx + GATE_HALF_W
    post = (70, 77, 84)
    tint = mix(mix(post, ACCENT, green), RED, red)
    # soft halo
    if green > 0 or red > 0:
        halo = mix(BG, (205, 238, 220), green) if green >= red else mix(BG, (250, 222, 218), red)
        rrect(d, (left - 22, GATE_TOP - 22, right + 22, GATE_BOTTOM + 14), 26, fill=halo)
    # bars (drawn first, they slide up behind the beam)
    bar_top = GATE_TOP + 16
    bar_bottom = lerp(GATE_BOTTOM - 4, bar_top + 6, ease(open_))
    n = 6
    for i in range(n):
        x = left + 16 + i * (2 * GATE_HALF_W - 32) / (n - 1)
        rrect(d, (x - 3.5, bar_top, x + 3.5, bar_bottom), 3, fill=tint)
        dot(d, (x, bar_bottom), 5, tint)                      # rounded spear tips
    # pillars and beam
    rrect(d, (left - 12, GATE_TOP, left + 6, GATE_BOTTOM), 6, fill=post)
    rrect(d, (right - 6, GATE_TOP, right + 12, GATE_BOTTOM), 6, fill=post)
    rrect(d, (left - 18, GATE_TOP - 6, right + 18, GATE_TOP + 16), 8, fill=post)
    # sign: the app icon on top of the beam
    d._image.paste(icon, (s(gx - 40), s(GATE_TOP - 92)), icon)
    text(d, (gx, GATE_BOTTOM + 14), "Wristgate", F_LABEL, INK, anchor="ma")


def draw_gate_arch(d, icon, open_, green, red, reason, icon_on_top=True):
    """Airport-style walk-through gate: a big arch with an LED display (name / reason).
    The app icon sits on top as the gate's sign and glows green (let through) or red (blocked)."""
    gx, gy = GATE
    body = GATE_BODY
    left, right = gx - ARCH_HALF_W, gx + ARCH_HALF_W
    top, bottom = ARCH_TOP, ARCH_BOTTOM
    side_w = 36
    state_col = mix(mix((150, 158, 166), ACCENT, green), RED, red)
    on = max(green, red)
    # soft glow behind the whole gate
    if on > 0:
        halo = mix(BG, (205, 238, 220), green) if green >= red else mix(BG, (250, 222, 218), red)
        rrect(d, (left - 22, top - (66 if icon_on_top else 22), right + 22, bottom + 12), 34, fill=halo)
    # arch: top beam, two side panels, feet
    rrect(d, (left, top, right, top + 92), 18, fill=body)
    rrect(d, (left, top + 40, left + side_w, bottom), 10, fill=body)
    rrect(d, (right - side_w, top + 40, right, bottom), 10, fill=body)
    rrect(d, (left - 8, bottom - 8, left + side_w + 8, bottom + 4), 4, fill=body)
    rrect(d, (right - side_w - 8, bottom - 8, right + 8, bottom + 4), 4, fill=body)
    # sensor strips on the side panels light up with the verdict
    for xc in (left + side_w / 2, right - side_w / 2):
        for k in range(6):
            yy = top + 108 + k * 27
            rrect(d, (xc - 4, yy, xc + 4, yy + 18), 4, fill=mix((70, 86, 120) if NIGHT else (95, 103, 111), state_col, on))
    # LED display on the beam
    rrect(d, (left + 16, top + 16, right - 16, top + 76), 10, fill=DISPLAY)
    if reason:
        msg, col = reason
    else:
        msg, col = "WRISTGATE", mix((12, 14, 16), AMBER, 0.9)
    lines = msg.split("\n")
    for i, line in enumerate(lines):
        yy = top + 46 + (i - (len(lines) - 1) / 2) * 22
        text(d, (gx, yy), line, F_LED, col, anchor="mm")
    if not icon_on_top:
        if NIGHT:
            pass                       # night: the name is the title at the top of the animation
        else:
            text(d, (gx, bottom + 16), "Wristgate", F_GATE_NAME, INK, anchor="ma")
        return
    # the app icon as the sign on top, with a coloured glow ring for the verdict
    ic = ICON_SIGN
    cy = top - ic / 2 + 6
    if on > 0.05:
        rrect(d, (gx - ic / 2 - 7, cy - ic / 2 - 7, gx + ic / 2 + 7, cy + ic / 2 + 7), 20,
              fill=mix(BG, state_col, on))
    d._image.paste(icon, (s(gx - ic / 2), s(cy - ic / 2)), icon)
    # name under the gate
    text(d, (gx, bottom + 16), "Wristgate", F_GATE_NAME, INK, anchor="ma")


def reason_tag(d, reason):
    """For the bars style: a small coloured tag with the reason, under the gate's name."""
    if not reason:
        return
    msg, col = reason
    msg = msg.replace("\n", " ")
    w = d.textlength(msg, font=F_TAG) / S + 20
    gx = GATE[0]
    y = GATE_BOTTOM + 36
    rrect(d, (gx - w / 2, y, gx + w / 2, y + 22), 11, fill=col)
    text(d, (gx, y + 11), msg, F_TAG, (255, 255, 255), anchor="mm")


def draw_watch(d, state, buzz, t):
    cx, cy = WATCH
    shake = math.sin(t * 90) * 3 * buzz
    cx += shake
    # straps
    rrect(d, (cx - 42, BAND_TOP + 6, cx + 42, cy - WATCH_R + 20), 16, fill=STRAP)
    rrect(d, (cx - 42, cy + WATCH_R - 20, cx + 42, BAND_BOTTOM - 6), 16, fill=STRAP)
    # buttons
    for dy in (-38, 38):
        rrect(d, (cx + WATCH_R + 2, cy + dy - 9, cx + WATCH_R + 12, cy + dy + 9), 4, fill=CASE)
        rrect(d, (cx - WATCH_R - 12, cy + dy - 9, cx - WATCH_R - 2, cy + dy + 9), 4, fill=CASE)
    # case and face
    ring = ACCENT if state != "idle" else CASE
    dot(d, (cx, cy), WATCH_R + 8, ring)
    dot(d, (cx, cy), WATCH_R, (12, 14, 16))
    # buzz arcs
    if buzz > 0.05:
        for side in (-1, 1):
            for k, rad in enumerate((WATCH_R + 26, WATCH_R + 40)):
                a = max(0, buzz - k * 0.25)
                if a <= 0:
                    continue
                start, end = (-30, 30) if side == 1 else (150, 210)
                d.arc([s(cx - rad), s(cy - rad), s(cx + rad), s(cy + rad)], start, end,
                      fill=mix(BG, ACCENT, a), width=s(4))
    # face content
    if state == "idle":
        text(d, (cx, cy - 6), "9:41", F_TIME, (235, 238, 240), anchor="mm")
        text(d, (cx, cy + 30), "TUE 24", F_SMALL, (140, 150, 158), anchor="mm")
    elif state == "msg":
        dot(d, (cx, cy - 52), 15, ACCENT)
        text(d, (cx, cy - 52), "…", F_W_NAME, (255, 255, 255), anchor="mm")
        text(d, (cx, cy - 14), "Mom", F_W_NAME, (255, 255, 255), anchor="mm")
        highlight(d, (cx, cy + 14), "Urgent, call me", F_W_TEXT, 1.0, anchor="mm")
        hb = d.textbbox((s(cx), s(cy + 14)), "Urgent, call me", font=F_W_TEXT, anchor="mm")
        d.text((hb[0], s(cy + 14)), "Urgent", font=F_W_TEXT, fill=INK, anchor="lm")
        d.text((hb[0] + d.textlength("Urgent", font=F_W_TEXT), s(cy + 14)), ", call me",
               font=F_W_TEXT, fill=(205, 212, 218), anchor="lm")
    elif state == "call":
        dot(d, (cx, cy - 46), 22, ACCENT)
        handset(d, (cx, cy - 46), 24, (255, 255, 255))
        text(d, (cx, cy + 2), "Mom", F_W_NAME, (255, 255, 255), anchor="mm")
        text(d, (cx, cy + 28), "Incoming call", F_W_TEXT, (205, 212, 218), anchor="mm")


def dashed(d, a, b, colour, dash=8, gap=7):
    (x0, y0), (x1, y1) = a, b
    length = math.hypot(x1 - x0, y1 - y0)
    n = int(length // (dash + gap)) + 1
    for i in range(n):
        t0 = i * (dash + gap) / length
        t1 = min(1, (i * (dash + gap) + dash) / length)
        if t0 >= 1:
            break
        d.line([s(lerp(x0, x1, t0)), s(lerp(y0, y1, t0)), s(lerp(x0, x1, t1)), s(lerp(y0, y1, t1))],
               fill=colour, width=s(3))


def pill(d, c, colour, alpha, cross=0.0):
    x, y = c
    rrect(d, (x - 18, y - 11, x + 18, y + 11), 11, fill=mix(BG, CARD, alpha), outline=mix(BG, LINE, alpha), width=1)
    dot(d, (x - 6, y), 5, mix(BG, colour, alpha))
    dot(d, (x + 7, y), 3, mix(BG, MUTED, alpha * 0.6))
    if cross > 0:
        cx, cy = x, y - 30
        dot(d, (cx, cy), 12, mix(BG, RED, cross))
        w = mix(mix(BG, RED, cross), (255, 255, 255), cross)
        d.line([s(cx - 5), s(cy - 5), s(cx + 5), s(cy + 5)], fill=w, width=s(3))
        d.line([s(cx + 5), s(cy - 5), s(cx - 5), s(cy + 5)], fill=w, width=s(3))


# ---------------------------------------------------------------- timeline
def frame(t_total, icon, icon_small=None, icon_inside=None):
    img = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(img)

    # which scene
    acc, idx = 0.0, len(EVENTS) - 1
    for i, dur in enumerate(SCENE):
        if t_total < acc + dur:
            idx = i
            break
        acc += dur
    t = t_total - acc if t_total < sum(SCENE) else SCENE[-1]
    ev = EVENTS[idx]
    allowed, kind = ev[3], ev[4]

    # phone: cards of past events + the current one sliding in from the top
    cards = []
    stack = [EVENTS[i] for i in range(idx + 1)]
    for j, e in enumerate(reversed(stack)):
        y_target = PHONE[1] + 44 + j * (CARD_H + 8)
        slide = ease(t / 0.35) if j == 0 else 1
        y = lerp(y_target - 10, y_target, slide) if j == 0 else y_target
        a = slide if j == 0 else 1
        if y + CARD_H < PHONE[3] - 8:
            cards.append((PHONE[0] + 12, y, e, a))
    draw_phone(d, cards)

    # route lines
    start = (PHONE[2] + 6, GATE[1])
    half = GATE_HALF_W if STYLE == "bars" else ARCH_HALF_W
    gate_in = (GATE[0] - half - 30, GATE[1])
    gate_out = (GATE[0] + half + 30, GATE[1])
    end = (WATCH[0] - WATCH_R - 26, GATE[1])
    dashed(d, start, (gate_in[0] + 16, GATE[1]), LINE)
    dashed(d, (gate_out[0] - 16, GATE[1]), end, LINE)

    open_ = green = red = 0.0
    pills = []
    inside = STYLE in ("inside", "night")
    # "inside": the notification goes all the way into the icon, which decides
    entry = GATE[0] if inside else gate_in[0]
    t1 = (t - 0.35) / (0.75 if inside else 0.6)  # phone -> gate
    if 0 <= t1 <= 1:
        pills.append(((lerp(start[0], entry, ease(t1)), GATE[1]), 1.0, 0.0))
    if t1 > 1:
        if allowed:
            # bars lift, the gate turns green, the notification goes through to the watch
            open_ = ease((t - 0.85) / 0.3) * (1 - ease((t - 1.75) / 0.3))
            green = max(0.0, min(1.0, (t - 0.85) / 0.25)) * (1 - ease((t - 1.8) / 0.4))
            t2 = (t - 1.0) / 0.65
            if 0 <= t2 <= 1:
                pills.append(((lerp(entry, end[0], ease(t2)), GATE[1]), 1.0, 0.0))
        else:
            # closed bars flash red, a red X pops up, the notification bounces back to the phone
            red = max(0.0, 1 - abs(t - 1.15) / 0.55)
            back = ease((t - 1.15) / 0.7)
            fade = 1 - ease((t - 1.5) / 0.5)
            cross = min(1.0, (t - 0.95) / 0.15) * fade
            if fade > 0:
                if inside:
                    # pushed back out of the icon, towards the phone; the X shows once it's out
                    x = lerp(entry, gate_in[0] - 20, back)
                    out_at = GATE[0] - (20 if NIGHT else ICON_INSIDE / 2 + 10)
                    pills.append(((x, GATE[1]), fade, cross if x < out_at else 0.0))
                else:
                    pills.append(((gate_in[0] - 70 * back, GATE[1]), fade, cross))
    reason = None
    if red > 0.05:
        reason = ("NO MATCH", mix((12, 14, 16), (255, 90, 80), min(1.0, red * 2)))
    elif green > 0.05:
        label = "KEYWORD:\nURGENT" if kind == "msg" else "INCOMING\nCALL"
        reason = (label, mix((12, 14, 16), (80, 230, 140), min(1.0, green * 2)))
    if STYLE == "bars":
        draw_gate(d, icon, open_, green, red)
        if reason:
            tag_col = RED if red > 0.05 else ACCENT
            reason_tag(d, (reason[0], mix(BG, tag_col, max(red, green))))
    elif inside:
        draw_gate_arch(d, None, open_, green, red, reason, icon_on_top=False)
    else:
        draw_gate_arch(d, icon_small, open_, green, red, reason)  # icon_small = sign-size icon
    for c, a_, x_ in pills:
        pill(d, c, ev[2], a_, x_)
    if inside and not NIGHT:
        # the icon in the passage, drawn over the notification: it "swallows" it while deciding
        ic = ICON_INSIDE
        gx, gy = GATE
        on = max(green, red)
        if on > 0.05:
            col = mix(mix((150, 158, 166), ACCENT, green), RED, red)
            rrect(d, (gx - ic / 2 - 8, gy - ic / 2 - 8, gx + ic / 2 + 8, gy + ic / 2 + 8), 28, fill=mix(BG, col, on))
        d._image.paste(icon_inside, (s(gx - ic / 2), s(gy - ic / 2)), icon_inside)

    # watch
    state, buzz = "idle", 0.0
    if allowed and t >= 1.65:
        state = kind
        since = t - 1.65
        if kind == "msg":
            buzz = max(0.0, 1 - since / 0.7)                       # one buzz
        else:
            buzz = max(0.0, 1 - (since % 0.9) / 0.55)              # buzz, buzz, buzz…
    elif idx >= 3 or (idx == 2 and False):
        state = "idle"
    draw_watch(d, state, buzz, t_total)

    # caption
    cap_a = ease(t / 0.3)
    text(d, (W / 2, H - 22), CAPTIONS[idx], F_CAPTION, mix(BG, INK, cap_a), anchor="ms")

    out = img.resize((W, H), Image.LANCZOS)
    if TITLE_H:
        # title band: the app's name, centred on top
        full = Image.new("RGB", (W * S, (H + TITLE_H) * S), BG)
        full.paste(img, (0, TITLE_H * S))
        td = ImageDraw.Draw(full)
        td.text((s(W / 2), s(TITLE_H - 4)), "Wristgate", font=F_TITLE, fill=INK, anchor="ms")
        out = full.resize((W, H + TITLE_H), Image.LANCZOS)
    return out


def main():
    icon = Image.open(ICON).convert("RGBA").resize((80 * S, 80 * S), Image.LANCZOS)
    total = sum(SCENE) + HOLD_END
    n = int(total * FPS)
    icon_small = Image.open(ICON).convert("RGBA").resize((ICON_SIGN * S, ICON_SIGN * S), Image.LANCZOS)
    icon_inside = Image.open(ICON).convert("RGBA").resize((ICON_INSIDE * S, ICON_INSIDE * S), Image.LANCZOS)
    global PLATE_ICON
    PLATE_ICON = Image.open(ICON).convert("RGBA").resize((26 * S, 26 * S), Image.LANCZOS)
    frames = [frame(i / FPS, icon, icon_small, icon_inside) for i in range(n)]
    # one shared palette keeps colours stable between frames (no flicker)
    # (built from frames of every scene, so no colour is missing — e.g. the boss's purple dot)
    picks = [frames[int(len(frames) * k / 8)] for k in range(8)]
    FH = frames[0].height
    sheet = Image.new("RGB", (W, FH * (len(picks) + 1)), BG)
    for i, f in enumerate(picks):
        sheet.paste(f, (0, i * FH))
    # big swatches of the key colours, so small dots still get exact palette entries
    sw = ImageDraw.Draw(sheet)
    for i, c in enumerate((GREEN, BLUE, PURPLE, ACCENT, RED, INK, MUTED, HIGHLIGHT)):
        sw.rectangle([i * 115, FH * len(picks), i * 115 + 105, FH * (len(picks) + 1)], fill=c)
    pal = sheet.quantize(colors=160, method=Image.Quantize.MEDIANCUT)
    out = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    out[0].save(OUT, save_all=True, append_images=out[1:], duration=int(1000 / FPS), loop=0, optimize=True)
    print(f"{OUT}: {len(out)} frames, {os.path.getsize(OUT) / 1024:.0f} KB")


if __name__ == "__main__":
    sys.exit(main())
