import { Fragment } from "react";

const OPEN = "<em>";
const CLOSE = "</em>";

/**
 * search-api가 <em>…</em>로 감싸 돌려준 하이라이트 문자열을 안전하게 렌더링.
 *
 * dangerouslySetInnerHTML을 쓰지 않고 직접 파싱하기 때문에 <em> 이외 태그는
 * 모두 문자열로 이스케이프된다. ES 응답에 <script> 등이 섞여 와도 실행되지 않음.
 */
export function HighlightedText({
  html,
  fallback,
  markClassName = "bg-amber-100 text-amber-900 px-0.5 rounded dark:bg-amber-900/40 dark:text-amber-200",
}: {
  html: string | undefined;
  fallback: string;
  markClassName?: string;
}) {
  if (!html) return <>{fallback}</>;

  const parts: Array<{ text: string; highlight: boolean }> = [];
  let i = 0;
  while (i < html.length) {
    const open = html.indexOf(OPEN, i);
    if (open === -1) {
      parts.push({ text: html.slice(i), highlight: false });
      break;
    }
    if (open > i) parts.push({ text: html.slice(i, open), highlight: false });
    const close = html.indexOf(CLOSE, open + OPEN.length);
    if (close === -1) {
      // 짝이 맞지 않으면 나머지는 일반 텍스트로 취급 — 깨진 응답 방어.
      parts.push({ text: html.slice(open), highlight: false });
      break;
    }
    parts.push({
      text: html.slice(open + OPEN.length, close),
      highlight: true,
    });
    i = close + CLOSE.length;
  }

  return (
    <>
      {parts.map((p, idx) =>
        p.highlight ? (
          <mark key={idx} className={markClassName}>
            {p.text}
          </mark>
        ) : (
          <Fragment key={idx}>{p.text}</Fragment>
        ),
      )}
    </>
  );
}
