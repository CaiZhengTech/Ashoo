import { Routes, Route, Navigate } from 'react-router-dom';
import { PersonaProvider } from './lib/PersonaContext';
import { ToastProvider } from './lib/ToastContext';
import Layout from './components/Layout';
import IntroGate from './components/IntroGate';
import Dashboard from './pages/Dashboard';
import LogPage from './pages/LogPage';
import InsightsPage from './pages/InsightsPage';
import RemindersPage from './pages/RemindersPage';
import PlacesPage from './pages/PlacesPage';
import NotFound from './pages/NotFound';

export default function App() {
  return (
    <PersonaProvider>
      <ToastProvider>
        <IntroGate />
        <Layout>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/log" element={<LogPage />} />
          <Route path="/insights" element={<InsightsPage />} />
          <Route path="/reminders" element={<RemindersPage />} />
          {/* Legacy path kept so old links/bookmarks still resolve. */}
          <Route path="/care" element={<Navigate to="/reminders" replace />} />
          <Route path="/places" element={<PlacesPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
        </Layout>
      </ToastProvider>
    </PersonaProvider>
  );
}
