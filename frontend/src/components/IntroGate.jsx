import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

/**
 * Dreamy cloud intro screen shown before the platform.
 *
 * Implemented as an OVERLAY (not a route): the real app is already mounted beneath
 * this, so dismissing the overlay reveals it with no route change or remount. On
 * "Access Demo" the whole scene gently zooms forward and fades, as if moving into
 * the clouds, then unmounts to show the dashboard.
 *
 * Shown once per browser session (sessionStorage): it survives in-app navigation
 * and refreshes, but a fresh visit gets the intro again, calm on first arrival,
 * never nagging during normal use.
 *
 * To remove the intro entirely, delete `<IntroGate />` from App.jsx.
 */

const SESSION_KEY = 'ashoo.introDone';

// The intro background. Pinterest "GIFs" are usually MP4 videos, so this supports
// either: point it at a .gif, .mp4, .webm, or .mov in /public/assets and it just
// works. If the file is missing it falls back to a soft sky gradient.
const INTRO_MEDIA = '/assets/intro.mp4';
const IS_VIDEO = /\.(mp4|webm|mov|m4v)$/i.test(INTRO_MEDIA);

const prefersReducedMotion =
  typeof window !== 'undefined' &&
  window.matchMedia &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export default function IntroGate() {
  const [done, setDone] = useState(() => sessionStorage.getItem(SESSION_KEY) === '1');
  const [leaving, setLeaving] = useState(false);
  const [gifFailed, setGifFailed] = useState(false);
  const [mediaReady, setMediaReady] = useState(false);
  const videoRef = useRef(null);
  const navigate = useNavigate();

  // Some browsers ignore the autoplay attribute but allow a programmatic play() on a
  // muted video. Kick it off explicitly and swallow the promise rejection (if it's
  // still blocked, the frame is shown frozen, which still looks fine).
  useEffect(() => {
    const v = videoRef.current;
    if (v) v.play?.().catch(() => {});
  }, []);

  if (done) return null;

  function accessDemo() {
    // Always enter on the dashboard, regardless of which route the page loaded on.
    navigate('/');
    setLeaving(true);
    // Let the zoom/fade play before unmounting; keep it short for reduced motion.
    const delay = prefersReducedMotion ? 250 : 750;
    window.setTimeout(() => {
      sessionStorage.setItem(SESSION_KEY, '1');
      setDone(true);
    }, delay);
  }

  // The whole scene transitions out: a gentle forward zoom + fade (fade only when
  // the user prefers reduced motion).
  const sceneTransition = prefersReducedMotion
    ? 'transition-opacity duration-300 ease-out'
    : 'transition-[opacity,transform] duration-700 ease-in';
  const sceneState = leaving
    ? prefersReducedMotion
      ? 'opacity-0'
      : 'opacity-0 scale-110'
    : 'opacity-100 scale-100';

  return (
    <div
      className={`fixed inset-0 z-50 overflow-hidden ${sceneTransition} ${sceneState} ${
        leaving ? 'pointer-events-none' : ''
      }`}
    >
      {/* Fallback sky, always present so the screen is calm and alive WHILE the video
          buffers (and if it is missing entirely). Soft drifting cloud blobs make this
          loading moment feel intentional rather than like lag. */}
      <div
        className="absolute inset-0 overflow-hidden"
        style={{ background: 'linear-gradient(180deg, #aacbe8 0%, #cfe0f0 38%, #eef2ee 100%)' }}
        aria-hidden
      >
        {!prefersReducedMotion && (
          <>
            <div
              className="absolute h-72 w-72 rounded-full blur-3xl animate-cloud-drift"
              style={{ top: '12%', left: '8%', background: 'radial-gradient(circle, rgba(255,255,255,0.85), transparent 70%)' }}
            />
            <div
              className="absolute h-96 w-96 rounded-full blur-3xl animate-cloud-drift"
              style={{ top: '40%', right: '6%', background: 'radial-gradient(circle, rgba(255,255,255,0.7), transparent 70%)', animationDelay: '-8s' }}
            />
            <div
              className="absolute h-64 w-64 rounded-full blur-3xl animate-cloud-drift"
              style={{ bottom: '8%', left: '32%', background: 'radial-gradient(circle, rgba(255,255,255,0.6), transparent 70%)', animationDelay: '-14s' }}
            />
          </>
        )}
      </div>

      {/* Cloud atmospheric layer (video or gif). object-cover fills the viewport
          without stretching; a slow drift adds life unless reduced motion is set.
          On load failure we hide it and the sky gradient above shows through. */}
      {!gifFailed &&
        (IS_VIDEO ? (
          <video
            ref={videoRef}
            src={INTRO_MEDIA}
            autoPlay
            loop
            muted
            playsInline
            preload="auto"
            onCanPlay={() => setMediaReady(true)}
            onError={() => setGifFailed(true)}
            className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-700 ${
              mediaReady ? 'opacity-100' : 'opacity-0'
            } ${prefersReducedMotion ? '' : 'animate-cloud-drift'}`}
          />
        ) : (
          <img
            src={INTRO_MEDIA}
            alt=""
            aria-hidden
            onLoad={() => setMediaReady(true)}
            onError={() => setGifFailed(true)}
            className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-700 ${
              mediaReady ? 'opacity-100' : 'opacity-0'
            } ${prefersReducedMotion ? '' : 'animate-cloud-drift'}`}
          />
        ))}

      {/* Polish overlays: a soft top-down scrim plus a centered radial vignette so
          white text stays readable over bright clouds without hiding the scene. */}
      <div
        className="absolute inset-0"
        style={{
          background:
            'radial-gradient(60% 55% at 50% 52%, rgba(15,30,40,0.42) 0%, rgba(15,30,40,0.10) 45%, transparent 75%),' +
            'linear-gradient(180deg, rgba(10,25,35,0.18) 0%, transparent 30%, transparent 65%, rgba(10,25,35,0.25) 100%)',
        }}
        aria-hidden
      />

      {/* Foreground content */}
      <div className="relative flex h-full w-full items-center justify-center px-6">
        <div className="animate-intro-rise flex flex-col items-center text-center">
          <h1
            className="text-5xl font-extrabold tracking-tight text-white sm:text-6xl"
            style={{ textShadow: '0 2px 22px rgba(10,25,40,0.5)' }}
          >
            Ashoo
          </h1>
          <p
            className="mt-3 max-w-md text-[15px] leading-relaxed text-white/90"
            style={{ textShadow: '0 1px 10px rgba(10,25,40,0.5)' }}
          >
            Understand how the air affects you. A calm, personal read on your
            air, pollen, and weather triggers.
          </p>

          <button
            onClick={accessDemo}
            className="group mt-8 inline-flex items-center gap-2 rounded-full border border-white/40 bg-white/15 px-7 py-3 text-sm font-semibold text-white shadow-lg ring-1 ring-white/10 backdrop-blur-md transition-all duration-200 hover:scale-[1.03] hover:bg-white/25 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-transparent"
          >
            Access Demo
            <span className="transition-transform duration-200 group-hover:translate-x-0.5" aria-hidden>
              →
            </span>
          </button>

          <p className="mt-5 text-xs text-white/70" style={{ textShadow: '0 1px 8px rgba(10,25,40,0.5)' }}>
            Weather and air quality data by Open-Meteo.com
          </p>
        </div>
      </div>
    </div>
  );
}
