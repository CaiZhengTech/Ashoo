import { createContext, useContext, useEffect, useState } from 'react';

// The persona currently being VIEWED. "you" is the real user; the others are the
// seeded demo personas the recruiter switcher exposes. Read-only surfaces
// (dashboard, briefing, insights) follow this selection; personal actions
// (logging, medications, consent) always stay with the real user.
const PersonaContext = createContext(null);

export const PERSONAS = [
  { key: 'you', name: 'You', blurb: 'Your own logged data', location: 'Sharon, MA' },
  { key: 'alex', name: 'Alex', blurb: 'Low, pollution only', location: 'London, United Kingdom' },
  { key: 'jordan', name: 'Jordan', blurb: 'Moderate, pollen season', location: 'Berlin, Germany' },
  { key: 'morgan', name: 'Morgan', blurb: 'High, many triggers', location: 'Paris, France' },
];

export function PersonaProvider({ children }) {
  const [persona, setPersona] = useState(() => localStorage.getItem('ashoo.persona') || 'you');

  useEffect(() => {
    localStorage.setItem('ashoo.persona', persona);
  }, [persona]);

  const value = {
    persona,
    setPersona,
    // What we send to the API: undefined for the real user, else the persona key.
    userParam: persona === 'you' ? undefined : persona,
    // Viewing any seeded persona means we're in illustrative/demo territory.
    isDemo: persona !== 'you',
    meta: PERSONAS.find((p) => p.key === persona) || PERSONAS[0],
  };

  return <PersonaContext.Provider value={value}>{children}</PersonaContext.Provider>;
}

export const usePersona = () => useContext(PersonaContext);
