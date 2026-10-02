"use client";

import { useEffect, useState } from "react";

// A timestamp in the VIEWER's time zone: "2026-10-01T17:25:00Z" -> "10-01 13:25 EDT" for a viewer
// in New York. Kept to month-day + 24h time because the /live activity strips are width-starved on a
// phone (the raw ISO string once took 58% of a row). The zone abbreviation stays on so the time is
// never ambiguous.
//
// The server cannot know the viewer's zone, so the first render (server + hydration) shows the UTC
// form ("10-01 17:25Z") and the effect swaps in local time on mount. Rendering local time on the
// server would format in the POD's zone and then mismatch on hydration.
export function LocalTime({ iso }: { iso: string }) {
  const [text, setText] = useState(() => utcShort(iso));
  const [title, setTitle] = useState(iso);

  useEffect(() => {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) {
      return;
    }
    setText(localShort(d));
    setTitle(d.toLocaleString(undefined, { timeZoneName: "short" }));
  }, [iso]);

  return (
    <time dateTime={iso} title={title}>
      {text}
    </time>
  );
}

function utcShort(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : `${d.toISOString().slice(5, 16).replace("T", " ")}Z`;
}

function localShort(d: Date): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat(undefined, {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZoneName: "short",
    })
      .formatToParts(d)
      .map((p) => [p.type, p.value]),
  );
  return `${parts.month}-${parts.day} ${parts.hour}:${parts.minute} ${parts.timeZoneName}`;
}
