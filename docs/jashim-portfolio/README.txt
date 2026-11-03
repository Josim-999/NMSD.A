JASHIM UDDIN — PORTFOLIO WEBSITE
=================================

This folder is a complete, ready-to-deploy website. All sample files are fictional.

HOW TO PUT IT ONLINE (pick one — both are free, ~1 minute):

OPTION A — Drag & drop (easiest, no tools)
  1. Go to  https://vercel.com/new
  2. Sign in (GitHub/Google/email).
  3. Click "Deploy" and look for the drag-and-drop / "Upload" option,
     OR install the Vercel CLI (Option B) if you don't see it.
  4. Drag this whole "jashim-portfolio" folder onto the page.
  5. Wait ~30 seconds. Vercel gives you a public link like
     https://jashim-portfolio.vercel.app  — share that with employers.

OPTION B — Vercel CLI (if you have Node.js)
  1. Open a terminal in this folder.
  2. Run:  npx vercel --prod
  3. Follow the prompts (accept defaults). It prints your public URL.

WHAT'S INSIDE
  index.html        -> the whole site (intro animation, 3D scene, all 12 projects,
                       sample lab). Open it in a browser to preview locally.
  samples/          -> the 2 source PDFs + text/CSV/JSON the sample lab serves.
                       The Excel files and ZIPs are generated in the browser when
                       a visitor clicks download, so they're not stored here.
  vercel.json       -> makes the sample files download instead of opening in-tab.

NOTES
  - Needs an internet connection to load fonts and the 3D library (both from
    public CDNs). No build step, no server code.
  - The page is one self-contained file; you can also host it on Netlify,
    GitHub Pages, or any static host the same way.
  - Contact shown on the site: princejosim80@gmail.com
