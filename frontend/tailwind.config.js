/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // Brand: a calm sky→teal "clean air" identity. Used for nav, links,
        // and primary actions — never for risk tiers (those are locked below).
        brand: {
          50: '#eefcfb',
          100: '#d5f6f4',
          200: '#aeece9',
          300: '#79ddd9',
          400: '#43c4c1',
          500: '#22a7a6',
          600: '#188585',
          700: '#176a6b',
          800: '#175455',
          900: '#174647',
        },
        ink: {
          // Warm-neutral text/canvas ramp — softer than pure gray, reads calmer.
          50: '#f8fafb',
          100: '#f1f4f6',
          200: '#e3e8ec',
          300: '#cdd5db',
          400: '#9aa6af',
          500: '#6b7884',
          600: '#4d5963',
          700: '#3a444d',
          800: '#262d34',
          900: '#171c21',
        },
        // PRI tiers — LOCKED to the CLAUDE.md risk scale. Color always means tier.
        tier: {
          great: '#16a34a',
          moderate: '#eab308',
          elevated: '#f97316',
          high: '#ef4444',
          severe: '#a855f7',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        card: '0 1px 2px rgba(16,24,40,0.04), 0 8px 24px -12px rgba(16,24,40,0.12)',
        'card-hover': '0 2px 4px rgba(16,24,40,0.06), 0 16px 40px -16px rgba(16,24,40,0.18)',
      },
      borderRadius: {
        '2xl': '1rem',
        '3xl': '1.5rem',
      },
      keyframes: {
        'fade-in-up': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        shimmer: {
          '100%': { transform: 'translateX(100%)' },
        },
        // Slow ken-burns drift for the cloud intro background.
        'cloud-drift': {
          '0%': { transform: 'scale(1.04)' },
          '100%': { transform: 'scale(1.16)' },
        },
        'intro-rise': {
          '0%': { opacity: '0', transform: 'translateY(14px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'fade-in-up': 'fade-in-up 0.4s ease-out both',
        shimmer: 'shimmer 1.5s infinite',
        'cloud-drift': 'cloud-drift 26s ease-in-out infinite alternate',
        'intro-rise': 'intro-rise 0.8s ease-out both',
      },
    },
  },
  plugins: [],
};
