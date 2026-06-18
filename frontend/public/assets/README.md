# Static assets

Files here are served from the web root by Vite.

## cloud intro background (gif OR video)

The intro screen reads one file, configured by `INTRO_MEDIA` at the top of
`src/components/IntroGate.jsx` (default `/assets/cloud-intro.gif`).

You can use either format, it auto-detects by extension:
- **GIF**: save as `cloud-intro.gif` (default, no code change needed).
- **MP4 / WebM**: save as e.g. `cloud-intro.mp4`, then set
  `INTRO_MEDIA = '/assets/cloud-intro.mp4'` in IntroGate.jsx. Video is smoother and
  much smaller than a GIF, so this is preferred for clouds.

Notes:
- Recommended: a seamless, slow cloud loop, landscape, ~1600x900 or larger.
- It is shown with `object-fit: cover`, so any aspect ratio works (it crops to fill).
- If the file is missing, the intro falls back to a soft CSS sky gradient.

### Getting the clip off Pinterest
Pinterest "GIFs" are usually MP4 videos. To grab one:
1. Open the pin in a browser, press F12, open the **Network** tab, filter by "Media".
2. Play the pin; an entry ending in `.mp4` appears, right-click it, "Open in new tab",
   then save it.
3. Or paste the pin URL into a reputable Pinterest video downloader.
4. Save the result here as `cloud-intro.mp4` and point `INTRO_MEDIA` at it.
