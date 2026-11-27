// The demo opens on Status. A static export cannot redirect on the server, so the root is a
// zero-delay refresh (the web server also redirects "/" itself, before this file is ever needed).
export default function Root() {
  return (
    <>
      <meta httpEquiv="refresh" content="0; url=/status/" />
      <main className="mx-auto max-w-6xl px-4 py-6 text-sm text-slate-400">
        <a className="underline" href="/status/">
          Open the demo
        </a>
      </main>
    </>
  );
}
