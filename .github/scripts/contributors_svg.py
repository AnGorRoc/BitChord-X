"""Draws the README's contributor grid from the live GitHub contributor list.

contrib.rocks used to draw it, but it caches each repo's list on its own side
and stopped picking up new contributors. This renders BitChord-X contributors
first at the top, followed by contributors from the original BitChord upstream,
with avatars embedded as base64 so the SVG renders standalone.

Usage: GH_TOKEN=... REPO=owner/name python contributors_svg.py out.svg
"""

import base64
import json
import os
import subprocess
import sys
import urllib.request
from xml.sax.saxutils import escape

COLUMNS = 12
SIZE = 64
GAP = 4
# Fetched at 2x so the circles stay sharp on high-density screens.
AVATAR_PX = SIZE * 2
# Accounts that show up in the commit history but aren't people or are AI assistants / bots.
EXCLUDE_KEYWORDS = {
    "claude",
    "copilot",
    "chatgpt",
    "gemini",
    "cursor",
    "antigravity",
    "deepseek",
    "openai",
    "openbot",
    "openbotgit",
    "hermes-agent",
    "github-actions",
    "dependabot",
    "sourcery",
    "coderabbit",
    "allcontributors",
}

# BitChord-X specific contributors in designated display priority order.
BITCHORDX_CONTRIBUTORS = [
    "AnGorRoc",
    "fvkinGordo",
    "Galaxyyss",
    "ArekatlaNishanthchowdary",
    "Hitomatito",
    "jhojhocraazy",
    "uveshm003",
    "VictorZanelo",
    "markandreydc",
    "peledson11",
    "Om-anand-0",
    "stuntsnakee",
]


def is_ai_or_bot(login):
    l = login.lower()
    return (
        "[bot]" in l
        or l.endswith("-bot")
        or l.endswith("_bot")
        or any(k in l for k in EXCLUDE_KEYWORDS)
    )


def get_bitchordx_logins():
    logins = {u.lower() for u in BITCHORDX_CONTRIBUTORS}
    try:
        # Check git log commits since v1.9 (04f64c9f11bd2714d01bbc6da401f146f3d9399f)
        out = subprocess.check_output(
            ["git", "log", "04f64c9f11bd2714d01bbc6da401f146f3d9399f..HEAD", "--format=%ae %an"],
            stderr=subprocess.DEVNULL,
        ).decode("utf-8", errors="ignore")
        for line in out.splitlines():
            line = line.strip().lower()
            if not line:
                continue
            if "noreply.github.com" in line:
                part = line.split()[0].split("@")[0]
                user = part.split("+")[-1]
                if not is_ai_or_bot(user):
                    logins.add(user)
    except Exception:
        pass
    return logins


def split_contributors(users):
    bx_logins = get_bitchordx_logins()
    bx_users = []
    upstream_users = []

    priority = {u.lower(): i for i, u in enumerate(BITCHORDX_CONTRIBUTORS)}

    for u in users:
        login = u.get("login", "")
        if not login or is_ai_or_bot(login):
            continue
        if login.lower() in bx_logins:
            bx_users.append(u)
        else:
            upstream_users.append(u)

    bx_users.sort(
        key=lambda c: (
            priority.get(c.get("login", "").lower(), 999),
            -c.get("contributions", 0),
        )
    )
    return bx_users, upstream_users


def get(url, token):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as res:
        return res.read(), res.headers


def contributors(repo, token):
    users, page = [], 1
    while True:
        body, _ = get(f"https://api.github.com/repos/{repo}/contributors?per_page=100&page={page}", token)
        batch = json.loads(body)
        if not batch:
            return users
        for c in batch:
            login = c.get("login", "")
            if c.get("type") == "User" and not is_ai_or_bot(login):
                users.append(c)
        page += 1


def avatar(c, token):
    url = c["avatar_url"] + ("&" if "?" in c["avatar_url"] else "?") + f"s={AVATAR_PX}"
    body, headers = get(url, token)
    mime = headers.get("Content-Type", "image/png").split(";")[0]
    return f"data:{mime};base64,{base64.b64encode(body).decode()}"


def render(bx_users, upstream_users, token):
    width = COLUMNS * SIZE + (COLUMNS - 1) * GAP
    r = SIZE // 2

    style = """<style>
  .label {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
    font-size: 13px;
    font-weight: 600;
    fill: #57606a;
    letter-spacing: 0.5px;
  }
  .rule {
    stroke: #d0d7de;
    stroke-width: 1;
  }
  @media (prefers-color-scheme: dark) {
    .label {
      fill: #8b949e;
    }
    .rule {
      stroke: #30363d;
    }
  }
</style>"""

    # BitChord-X Section
    s1_rows = max(1, -(-len(bx_users) // COLUMNS))
    s1_h = s1_rows * SIZE + (s1_rows - 1) * GAP
    s1_y = 28

    # Upstream Section
    divider_y = s1_y + s1_h + 16
    s2_label_y = divider_y + 20
    s2_y = s2_label_y + 12
    s2_rows = max(1, -(-len(upstream_users) // COLUMNS))
    s2_h = s2_rows * SIZE + (s2_rows - 1) * GAP

    total_height = s2_y + s2_h

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        f'width="{width}" height="{total_height}" viewBox="0 0 {width} {total_height}">'
    ]
    out.append(style)

    # Section 1 Header
    out.append(f'<text x="0" y="16" class="label">BitChord-X Contributors ({len(bx_users)})</text>')

    # Section 1 Grid
    idx = 0
    for i, c in enumerate(bx_users):
        x = (i % COLUMNS) * (SIZE + GAP)
        y = s1_y + (i // COLUMNS) * (SIZE + GAP)
        login = escape(c["login"])
        out.append(
            f'<svg x="{x}" y="{y}" width="{SIZE}" height="{SIZE}"><title>{login}</title>'
            f'<defs><pattern id="a{idx}" width="{SIZE}" height="{SIZE}" patternUnits="userSpaceOnUse">'
            f'<image width="{SIZE}" height="{SIZE}" xlink:href="{avatar(c, token)}"/></pattern></defs>'
            f'<circle cx="{r}" cy="{r}" r="{r - 0.5}" stroke="#c0c0c0" stroke-width="1" fill="url(#a{idx})"/></svg>'
        )
        idx += 1

    # Divider & Section 2 Header
    out.append(f'<line x1="0" y1="{divider_y}" x2="{width}" y2="{divider_y}" class="rule"/>')
    out.append(f'<text x="0" y="{s2_label_y}" class="label">Original BitChord Contributors ({len(upstream_users)})</text>')

    # Section 2 Grid
    for i, c in enumerate(upstream_users):
        x = (i % COLUMNS) * (SIZE + GAP)
        y = s2_y + (i // COLUMNS) * (SIZE + GAP)
        login = escape(c["login"])
        out.append(
            f'<svg x="{x}" y="{y}" width="{SIZE}" height="{SIZE}"><title>{login}</title>'
            f'<defs><pattern id="a{idx}" width="{SIZE}" height="{SIZE}" patternUnits="userSpaceOnUse">'
            f'<image width="{SIZE}" height="{SIZE}" xlink:href="{avatar(c, token)}"/></pattern></defs>'
            f'<circle cx="{r}" cy="{r}" r="{r - 0.5}" stroke="#c0c0c0" stroke-width="1" fill="url(#a{idx})"/></svg>'
        )
        idx += 1

    out.append("</svg>")
    return "\n".join(out) + "\n"


def main():
    token = os.environ.get("GH_TOKEN", "")
    users = contributors(os.environ["REPO"], token)
    bx_users, upstream_users = split_contributors(users)
    svg = render(bx_users, upstream_users, token)
    with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as f:
        f.write(svg)
    print(
        f"{len(bx_users)} BitChord-X, {len(upstream_users)} upstream contributors, "
        f"{len(svg) // 1024} KiB"
    )


if __name__ == "__main__":
    main()
