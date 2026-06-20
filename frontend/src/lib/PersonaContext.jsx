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
  const [youLocation, setYouLocationState] = useState(
    () => localStorage.getItem('ashoo.youLocation') || 'Sharon, MA'
  );

  useEffect(() => {
    localStorage.setItem('ashoo.persona', persona);
  }, [persona]);

  function setYouLocation(city) {
    setYouLocationState(city);
    localStorage.setItem('ashoo.youLocation', city);
  }

  const personas = PERSONAS.map((p) =>
    p.key === 'you' ? { ...p, location: youLocation } : p
  );

  const value = {
    persona,
    setPersona,
    userParam: persona === 'you' ? undefined : persona,
    isDemo: persona !== 'you',
    meta: personas.find((p) => p.key === persona) || personas[0],
    setYouLocation,
  };

  return <PersonaContext.Provider value={value}>{children}</PersonaContext.Provider>;
}

export const usePersona = () => useContext(PersonaContext);
